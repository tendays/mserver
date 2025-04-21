/**
 * 
 */
package org.gamboni.mserver;

import com.google.common.collect.AbstractIterator;
import com.google.common.collect.ImmutableList;
import com.google.common.util.concurrent.Uninterruptibles;
import lombok.Getter;
import lombok.ToString;
import org.gamboni.mserver.data.GlobalState;
import org.gamboni.mserver.data.Item;
import org.gamboni.mserver.data.PlayState;
import org.gamboni.mserver.data.PlayingGlobalState;
import org.gamboni.mserver.tech.AbstractController;
import org.gamboni.mserver.tech.Mapping;
import org.gamboni.mserver.ui.DirectoryPage;
import org.gamboni.tech.history.HistoryStore;
import org.gamboni.tech.web.js.JavaScript.JsExpression;

import java.io.BufferedReader;
import java.io.File;
import java.io.IOException;
import java.net.UnixDomainSocketAddress;
import java.nio.ByteBuffer;
import java.nio.channels.Channels;
import java.nio.channels.SocketChannel;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.function.Consumer;

import static com.google.common.base.Preconditions.checkNotNull;
import static org.gamboni.tech.web.js.JavaScript.literal;

/**
 * @author tendays
 *
 */
public class MServerController extends AbstractController {

	private static final String SOCKET = "/tmp/mserver";

	final File root;
	private final ImmutableList<String> extraPlayerArgs;
	private final MServerSocket socketHandler;
	private Optional<Process> mpvProcess = Optional.empty();
	private Optional<SocketClient> mpvClient = Optional.empty();

	@Getter
	private final MServerHistoryStore store;

	private final List<Item> queue = new ArrayList<>();

	/**
	 * If present, contains the folder from which to get a random next item to auto-play.
	 */
	private Optional<File> shuffleFolder = Optional.empty();

	private final ServiceProxy play;
	private final ServiceProxy shuffle;

	public final JsExpression pause = service("pause", () -> {
		ifRunning(mp -> {
			mp.write("{\"command\": [\"cycle\", \"pause\"]}");
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
			mp.write("{\"command\": [\"quit\"]}");
		});
	}

	public MServerController(Mapping mapping, MServerSocket socketHandler, File folder, List<String> extraPlayerArgs) {
		super(mapping);
		this.store = new MServerHistoryStore(mapping);
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
								pointer -> store.isNowPlaying(pointer) ?
										DirectoryPage.PLAY_STATE_FUNCTION.apply(
										store.getGlobalState().getClass()) : PlayState.QUEUED);
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
		ImmutableList<String> commandLine = ImmutableList.<String>builder()
				.add("mpv", "--input-ipc-server=" + SOCKET, "--vo=null")
				.addAll(extraPlayerArgs)
				.add(item.file.getPath())
				.build();

		System.err.println("$ " + String.join(" ", commandLine));

		this.mpvProcess = Optional.of(Runtime.getRuntime().exec(
				commandLine
						.toArray(new String[0])));
		updateStore(s -> {
			Optional<File> playingBefore = s.getNowPlaying();
			s.setNowPlaying(item.file);

			// see if we need to remove "playing" status of oldState
			playingBefore.ifPresent(toRemove ->
				s.setFileState(ancestors(toRemove),
						pointer -> isQueued(pointer) ? PlayState.QUEUED : PlayState.STOPPED));
			s.setFileState(ancestors(item.file), PlayState.PLAYING);

			s.setGlobalState(new PlayingGlobalState(Instant.now(), 0));
		});
	}

	@ToString
	static class MpvMessage {
		public String event;
		public Long id;
		public String name;
		public String data;
		public String error;
		public Long request_id;
	}

	private class SocketClient {
		SocketChannel mpvSocket = openMpvSocketConnection();
		BufferedReader reader = openReader(mpvSocket);
		long nextRequestId = 1;
		Long durationProperty;
		Long positionProperty;
		double duration = 0;
		double position = 0;

		void init() throws IOException {
			duration = 0;
			position = 0;

			// Duration is not supposed to change, but I've sometimes seen it being null when querying too soon,
			// so we "observe" it to be sure we eventually get the correct value.
			durationProperty = (nextRequestId++);
			write("{\"command\":[\"observe_property_string\", "+ durationProperty +", \"duration\"]}");

			positionProperty = (nextRequestId++);
			write("{\"command\":[\"observe_property_string\", "+ positionProperty +", \"playback-time\"]}");
		}

		void poll() throws IOException {
			String line = null;
			try {
				line = reader.readLine(); // blocking
			} catch (IOException ioX) {
				System.err.println(ioX.getMessage());
				// likely MPV terminated, let's reconnect
			}
			if (line == null || !mpvProcess.get().isAlive()) {
				mpvProcess = Optional.empty();
				if (pop()) {
					reconnect();
				}
				return;
			}
					/* example messages:

					{"event":"property-change","id":1,"name":"playback-time","data":39.040266}
					{"request_id":0,"error":"invalid parameter"}
					{"data":"374.955828","request_id":12,"error":"success"}
					 */
			var message = mapping.readValue(line, MpvMessage.class);
			if (positionProperty.equals(message.id)) {
				position = (message.data == null) ? 0 : Double.parseDouble(message.data) * 1000;
			} else if (durationProperty.equals(message.id)) {
				duration =  (message.data == null) ? 0 : Double.parseDouble(message.data) * 1000;
			} else {
				System.err.println(line);
				return;
			}

			updateStore(s -> s.setGlobalState(new PlayingGlobalState(
					Instant.now().minusMillis((long) position),
					duration)));
		}

		void close() {
			tryClose(mpvSocket);
			mpvSocket = null;
			reader = null;
		}

		void reconnect() throws IOException {
			RuntimeException lastError = null;
			int tries = 0;
			while (tries < 5) {
				try {
					close();

					mpvSocket = openMpvSocketConnection();
					reader = openReader(mpvSocket);
					init();
					return;
				} catch (IOException e) {
					tries++;
					lastError = new RuntimeException(e);
					System.err.println("Socket re-connection attempt " + tries + " failed with " + e);
					Uninterruptibles.sleepUninterruptibly(Duration.ofMillis(500));
				}
			}
			System.err.println("Giving up re-connection after " + tries + " attempts");
			throw lastError;
		}

		private void write(String string) throws IOException {
			System.err.println("> "+ string);
			mpvSocket.write(ByteBuffer.wrap((string + "\n").getBytes(StandardCharsets.UTF_8)));
		}
	}

	private static SocketChannel openMpvSocketConnection() {
		RuntimeException lastError = null;
		int tries = 0;
		while (tries < 5) {
			try {
				return SocketChannel.open(UnixDomainSocketAddress.of(SOCKET));
			} catch (IOException e) {
				tries++;
				lastError = new RuntimeException(e);
				System.err.println("Socket connection attempt " + tries +" failed with "+ e);
				Uninterruptibles.sleepUninterruptibly(Duration.ofMillis(500));
			}
		}
		System.err.println("Giving up connection after "+ tries +" attempts");
		throw lastError;
	}

	private static BufferedReader openReader(SocketChannel channel) {
		return new BufferedReader(Channels.newReader(channel, StandardCharsets.UTF_8));
	}

	private void startStatusThread() {
		new Thread(() -> {
			SocketClient client = new SocketClient();
			try {
				client.init();
				mpvClient = Optional.of(client);
				while (isRunning()) {
					client.poll();
				}
			} catch (IOException e) {
				e.printStackTrace();
			} finally {
				client.close();
				mpvClient = Optional.empty();
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

	private void tryClose(AutoCloseable r) {
		try {
			if (r != null) {
				r.close();
			}
		} catch (Exception e) {
			e.printStackTrace();
		}
	}

	private synchronized boolean isRunning() {
		return mpvProcess.isPresent();
	}

	private interface ThrowingProcessConsumer {
		void accept(SocketClient socketClient) throws IOException;
	}

	/**
	 * Pass the MPlayer Process, if available, to the given consumer.
	 */
	private void ifRunning(ThrowingProcessConsumer consumer) throws IOException {
		Optional<SocketClient> copy;
		synchronized (this) {
			copy = this.mpvClient;
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
