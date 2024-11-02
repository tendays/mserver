/**
 * 
 */
package org.gamboni.mserver;

import com.google.common.base.Joiner;
import com.google.common.collect.AbstractIterator;
import com.google.common.collect.ImmutableList;
import lombok.Getter;
import org.gamboni.mserver.data.GlobalState;
import org.gamboni.mserver.data.Item;
import org.gamboni.mserver.data.PlayState;
import org.gamboni.mserver.tech.AbstractController;
import org.gamboni.mserver.tech.Mapping;
import org.gamboni.tech.history.HistoryStore;
import org.gamboni.tech.web.js.JavaScript.JsExpression;

import java.io.*;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.function.Consumer;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static com.google.common.base.Preconditions.checkNotNull;
import static org.gamboni.tech.web.js.JavaScript.literal;

/**
 * @author tendays
 *
 */
public class MServerController extends AbstractController {

	final File root;
	private final ImmutableList<String> extraPlayerArgs;
	private Optional<Process> mplayer = Optional.empty();
	private final MServerSocket socketHandler;

	@Getter
	private final MServerHistoryStore store = new MServerHistoryStore();

	private final List<Item> queue = new ArrayList<>();

	/**
	 * If present, contains the folder from which to get a random next item to auto-play.
	 */
	private Optional<File> shuffleFolder = Optional.empty();

	private static final Pattern STATUS_LINE = Pattern.compile("A: *([0-9.]*) \\(([0-9:.]*)\\) of ([0-9.]*).*");

	// mplayer  -vc dummy -vo null -noconsolecontrols  -really-quiet -vo null -ao pulse::1
	private final ServiceProxy play;
	private final ServiceProxy shuffle;

	public final JsExpression pause = service("pause", () -> {
		ifRunning(mp -> {
			OutputStream out = mp.getOutputStream();
			out.write(' ');
			out.flush();
			updateStore(s -> s.setGlobalState(s.getGlobalState().togglePaused()));
		});
		return "ok";
	});

	public final JsExpression skip = service("skip", () -> {
		skip();
		return "ok";
	});

	public final JsExpression stop = service("stop", () -> {
		stop();
		return "ok";
	});

	private void stop() throws IOException {
		// stop shuffling, ...
		this.shuffleFolder = Optional.empty();

		// ... clear the queue, ...
		updateStore(session -> {
			var iterator = queue.iterator();
			while (iterator.hasNext()) {
				var item = iterator.next();
				session.setFileState(ancestors(item.file), PlayState.STOPPED);
				iterator.remove();
			}
		});

		// ... and end the current song
		skip();
	}

	private void skip() throws IOException {
		ifRunning(mp -> {
			OutputStream out = mp.getOutputStream();
			out.write('\n');
			out.flush();
		});
	}

	public MServerController(Mapping mapping, MServerSocket socketHandler, File folder, List<String> extraPlayerArgs) {
		super(mapping);
		this.socketHandler = socketHandler;
		this.root = folder;
		this.extraPlayerArgs = ImmutableList.copyOf(extraPlayerArgs);
		socketHandler.setController(this);

		this.play = service("play", fileName -> {
			synchronized (this) {
				Item item = new Item(mapping.pathToFile(fileName));
				if (isRunning()) {
					queue.add(item);
					System.out.println("Queued " + item);
					var notifications = store.update(session -> {
						session.setFileState(ancestors(item.file),
								pointer -> store.isNowPlaying(pointer) ? store.getGlobalState().state() : PlayState.QUEUED);
					});

					broadcastState(notifications);
				} else {
					playNow(item);
					startStatusThread();
				}
			}
			return "ok";
		});

		this.shuffle = service("shuffle", folderName -> {
			synchronized (this) {
				if (isRunning()) {
					// act like a toggle: if something is playing, stop it; will play on next
					// request
					stop();
					return "ok";
				} else {
					this.shuffleFolder = Optional.of(mapping.pathToFile(folderName));
					if (shuffleOne()) {
						startStatusThread();
						return "ok";
					} else {
						return "ko";
					}
				}
			}
		});
	}

	private boolean shuffleOne() {
		return shuffleFolder.map(f -> {
			File[] files = f.listFiles(file -> new Item(file).isMusic());
			if (files == null || files.length == 0) {
				return false;
			} else {
				Item item = new Item(files[(int) (Math.random() * files.length)]);
				try {
					playNow(item);
				} catch (IOException e) {
					throw new RuntimeException(e);
				}
				return true;
			}
		}).orElse(false);
	}

	/**
	 * Before: mplayer.isAbsent(). After: mplayer.isPresent()
	 */
	private void playNow(Item item) throws IOException {
		System.out.println("mplayer -vc dummy -vo null " + Joiner.on(' ').join(extraPlayerArgs)
				+ item.file);
		this.mplayer = Optional.of(Runtime.getRuntime().exec(
				ImmutableList.<String>builder()
						.add("mplayer", "-vc", "dummy", "-vo", "null")
						.addAll(extraPlayerArgs)
						.add(item.file.getPath())
						.build()
						.toArray(new String[0])));
		updateStore(s -> {
			Optional<File> playingBefore = s.getNowPlaying();
			s.setNowPlaying(item.file);

			// see if we need to remove "playing" status of oldState
			playingBefore.ifPresent(toRemove ->
				s.setFileState(ancestors(toRemove),
						pointer -> isQueued(pointer) ? PlayState.QUEUED : PlayState.STOPPED));
			s.setFileState(ancestors(item.file), PlayState.PLAYING);

			s.setGlobalState(GlobalState.PLAYING);
		});
	}

	private void startStatusThread() {
		new Thread(() -> {
			BufferedReader r = null;
			try {
				r = new BufferedReader(new InputStreamReader(mplayer.get().getInputStream()));
				while (isRunning()) {
					String line = r.readLine(); // blocking
					if (line == null || !mplayer.get().isAlive()) {
						mplayer = Optional.empty();
						if (pop()) {
							tryClose(r);
							r = new BufferedReader(new InputStreamReader(mplayer.get().getInputStream()));
							continue;
						} else {
							return;
						}
					}

					Matcher m = STATUS_LINE.matcher(line);
					if (m.matches()) {
						updateStore(s -> s.setGlobalState(new GlobalState(
								s.getGlobalState().state(),
								Double.parseDouble(m.group(1)),
								Double.parseDouble(m.group(3)),
								m.group(2))));
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

	/**
	 * Pop the next item from the queue, if any, and play it.
	 *
	 * @return true if an item was popped, false if the queue was empty
	 * @throws IOException
	 */
	private synchronized boolean pop() throws IOException {
		if (!queue.isEmpty()) {
			Item toPlay = queue.remove(0);
			playNow(toPlay);
			return true;
		} else if (shuffleFolder.isPresent() && shuffleOne()) {
			return true;
		} else {
			updateStore(s -> {
				s.setGlobalState(GlobalState.STOPPED);
				Optional<File> playingBefore = s.getNowPlaying();
				s.clearNowPlaying();
				// see if we need to remove "playing" status of oldState
				playingBefore.ifPresent(toRemove ->
						s.setFileState(ancestors(toRemove),
								pointer -> isQueued(pointer) ? PlayState.QUEUED : PlayState.STOPPED));
			});
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
		return mplayer.isPresent();
	}

	private interface ThrowingProcessConsumer {
		void accept(Process process) throws IOException;
	}

	/**
	 * Pass the MPlayer Process, if available, to the given consumer.
	 */
	private void ifRunning(ThrowingProcessConsumer consumer) throws IOException {
		Optional<Process> copy;
		synchronized (this) {
			copy = this.mplayer;
		}
		if (copy.isPresent()) {
			consumer.accept(copy.get());
		}
	}

	/**
	 * True if {@code path} is either the name of an item in queue, or one of its parent directories.
	 */
	private boolean isQueued(File path) {
		return queue.stream().anyMatch(item -> item.isAt(path));
	}

	/**
	 * Return ancestors of the given File (included) up to, but not including, the root folder.
	 */
	private Iterable<File> ancestors(File file) {
		return () -> new AbstractIterator<>() {
			File pointer = file;

			@Override
			protected File computeNext() {
				if (pointer.equals(root)) {
					return endOfData();
				}
				File result = pointer;
				pointer = checkNotNull(pointer.getParentFile());
				return result;
			}
		};
	}

	private void updateStore(Consumer<MServerHistoryStore.UpdateSession> work) {
		broadcastState(store.update(work));
	}

	/** Return JavaScript code to play the given file. */
	public JsExpression jsPlay(File file) {
		return play.call(literal(mapping.fileToPath(file)));
	}


	public void broadcastState(HistoryStore<?, ?, ?>.PerClientUpdates notifications) {
		if (notifications.hasEvents()) {
			socketHandler.broadcast(notifications::get);
		}
	}
}
