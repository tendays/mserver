/**
 * 
 */
package org.gamboni.mserver;

import com.google.common.base.Joiner;
import com.google.common.collect.ImmutableList;
import org.gamboni.mserver.data.Item;
import org.gamboni.mserver.data.PlayState;
import org.gamboni.mserver.data.Status;
import org.gamboni.mserver.tech.AbstractController;
import org.gamboni.tech.web.js.JavaScript.JsExpression;

import java.io.*;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.function.UnaryOperator;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * @author tendays
 *
 */
public class MServerController extends AbstractController {

	private final MServer owner;
	final File folder;
	private final ImmutableList<String> extraPlayerArgs;
	private Process mplayer; // TODO Optional
	private final MServerSocket socketHandler;
	private volatile Status status = Status.STOPPED;

	private final List<Item> queue = new ArrayList<>();
	/** If not null, contains the folder from which to get a random next item to auto-play. */
	private File shuffleFolder = null;

	private static final Pattern STATUS_LINE = Pattern.compile("A: *([0-9.]*) \\(([0-9:.]*)\\) of ([0-9.]*).*");

	// mplayer  -vc dummy -vo null -noconsolecontrols  -really-quiet -vo null -ao pulse::1
	private final ServiceProxy play;
	private final ServiceProxy shuffle;

	public final JsExpression pause = service("pause", () -> {
		if (isRunning()) {
			OutputStream out = mplayer.getOutputStream();
			out.write(' ');
			out.flush();
			updateStatus(s -> new Status(s.nowPlaying(), s.state().togglePaused(), s.position(), s.duration(), s.time()));
		}
		return status;
	});

	public final JsExpression stop = service("stop", () -> {
		stop();
		return status;
	});

	private void stop() throws IOException {
		if (isRunning()) {
			OutputStream out = mplayer.getOutputStream();
			out.write('\n');
			out.flush();
			// on stop, keep playing things from the queue, but don't add more through shuffle
			shuffleFolder = null;
		}
	}

	public MServerController(MServer owner, MServerSocket socketHandler, File folder, List<String> extraPlayerArgs) {
		this.owner = owner;
		this.socketHandler = socketHandler;
		this.folder = folder;
		this.extraPlayerArgs = ImmutableList.copyOf(extraPlayerArgs);
		socketHandler.setController(this);

		this.play = service("play", fileName -> {
			synchronized (this) {
				Item item = new Item(owner, new File(folder, fileName));
				if (isRunning()) {
					queue.add(item);
					System.out.println("Queued "+ item);
				} else {
					playNow(item);
					startStatusThread(fileName);
				}
			}
			return "ok";
		});

		this.shuffle = service("shuffle", folderName -> {
			synchronized (this) {
				if (isRunning()) {
					// act like a toggle: if something is playing, stop it; will play on next
					// request
					queue.clear();
					stop();
					return "ok";
				} else {
					this.shuffleFolder = new File(folder, folderName);
					return shuffleOne()
							.map(played -> {
								startStatusThread(played.name);
								return "ok";
							}).orElse("ko");
				}
			}
		});
	}

	private Optional<Item> shuffleOne() throws IOException {
		File[] files = shuffleFolder.listFiles(file -> new Item(owner, file).isMusic());
		if (files == null || files.length == 0) {
			return Optional.empty();
		} else {
			Item item = new Item(owner, files[(int)(Math.random() * files.length)]);
			playNow(item);
			return Optional.of(item);
		}
	}

	/** Before: mplayer == null. After: mplayer != null */
	private void playNow(Item item) throws IOException {
		System.out.println("mplayer -vc dummy -vo null " + Joiner.on(' ').join(extraPlayerArgs)
				+ item.file);
		// TODO prevent path traversal
		this.mplayer = Runtime.getRuntime().exec(
				ImmutableList.<String>builder()
						.add("mplayer", "-vc", "dummy", "-vo", "null")
						.addAll(extraPlayerArgs)
						.add(item.file.toString())
						.build()
						.toArray(new String[0]));
	}

	private void startStatusThread(String fileName) {
		this.updateStatus(__ -> new Status(fileName, PlayState.PLAYING, 0, 0, "00:00.0"));
		new Thread(() -> {
			BufferedReader r = null;
			try {
				r = new BufferedReader(new InputStreamReader(mplayer.getInputStream()));
				while (isRunning()) {
					String line = r.readLine(); // blocking
					if (line == null || !mplayer.isAlive()) {
						mplayer = null;
						if (pop()) {
							tryClose(r);
							r = new BufferedReader(new InputStreamReader(mplayer.getInputStream()));
							continue;
						} else {
							return;
						}
					}

					Matcher m = STATUS_LINE.matcher(line);
					if (m.matches()) {
						updateStatus(s -> new Status(
								s.nowPlaying(),
								s.state(),
								Double.parseDouble(m.group(1)),
								Double.parseDouble(m.group(3)),
								m.group(2)));
					} else {
						System.out.println(line);
					}
				}
			} catch (IOException e) {
				e.printStackTrace();
			} finally {
				tryClose(r);
			}
		}).start();
	}

	/** Pop the next item from the queue, if any, and play it.
	 *
	 * @return true if an item was popped, false if the queue was empty
	 * @throws IOException
	 */
	private synchronized boolean pop() throws IOException {
		if (!queue.isEmpty()) {
			playNow(queue.remove(0));
			return true;
		} else if (shuffleFolder != null && shuffleOne().isPresent()) {
			return true;
		} else {
			updateStatus(__ -> Status.STOPPED);
			return false;
		}
	}

	private void tryClose(BufferedReader r) {
		try {
			if (r != null) {
				r.close();
			}
		} catch (IOException e) {
			e.printStackTrace();
		}
	}

	private synchronized boolean isRunning() {
		return (mplayer != null);
	}

	private void updateStatus(UnaryOperator<Status> op) {
		Status oldState, newState;
		synchronized (this) {
			oldState = status;
			status = op.apply(status);
			newState = status;
		}
		if (oldState.state() != newState.state() ||
				// when starting a new item duration switches from 0 to actual value;
				// we want to notify the front ends at that time
				oldState.duration() != newState.duration()) {
			socketHandler.broadcastState(newState);
		}
	}

	public Status getCurrentStatus() {
		return this.status;
	}

	public JsExpression play(JsExpression param) {
		return play.call(param);
	}
}
