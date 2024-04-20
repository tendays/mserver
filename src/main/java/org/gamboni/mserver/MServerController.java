/**
 * 
 */
package org.gamboni.mserver;

import java.io.BufferedReader;
import java.io.File;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.function.UnaryOperator;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.gamboni.mserver.data.Item;

import com.google.common.base.Joiner;
import com.google.common.collect.ImmutableList;
import org.gamboni.mserver.data.StatusDTO;
import org.gamboni.mserver.tech.AbstractController;
import org.gamboni.tech.web.js.JavaScript.JsExpression;

/**
 * @author tendays
 *
 */
public class MServerController extends AbstractController {

	private final MServer owner;
	final File folder;
	private final ImmutableList<String> extraPlayerArgs;
	private Process mplayer;
	private volatile Status status;
	
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
			updateStatus(s -> new Status(s.nowPlaying, !s.paused, s.position, s.length, s.time));
		}
		return status();
	});
	
	public final JsExpression stop = service("stop", () -> {
		stop();
		return status();
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
	
	public final CallbackServiceProxy getStatus = getService("status", () -> status());
	
	public MServerController(MServer owner, File folder, List<String> extraPlayerArgs) {
		this.owner = owner;
		this.folder = folder;
		this.extraPlayerArgs = ImmutableList.copyOf(extraPlayerArgs);
		
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
		status = new Status(fileName, false, 0, 0, "00:00.0");
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
								s.nowPlaying,
								s.paused,
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
			status = null;
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

	private synchronized void updateStatus(UnaryOperator<Status> op) {
		status = op.apply(status);
	}

	public JsExpression play(JsExpression param) {
		return play.call(param);
	}

	private StatusDTO status() {
		Status copy = this.status;
		if (copy == null) { return status("ready", 0); }
		return status(
			(copy.paused ? "paused " : "playing ") + copy.time,
			(copy.length > 0 ? copy.position / copy.length * 100 : 0));
	}
	
	private StatusDTO status(String text, double progress) {
		return new StatusDTO(text, progress +"%");
	}
}
