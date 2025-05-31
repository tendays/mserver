/**
 * 
 */
package org.gamboni.mserver;

import com.google.common.collect.AbstractIterator;
import lombok.Getter;
import org.gamboni.mserver.data.GlobalState;
import org.gamboni.mserver.data.Item;
import org.gamboni.mserver.data.PausedGlobalState;
import org.gamboni.mserver.data.PlayState;
import org.gamboni.mserver.data.PlayingGlobalState;
import org.gamboni.mserver.tech.AbstractController;
import org.gamboni.mserver.tech.Mapping;
import org.gamboni.mserver.tech.media.MediaPlayer;
import org.gamboni.mserver.tech.media.MpvMediaPlayer;
import org.gamboni.mserver.ui.DirectoryPage;
import org.gamboni.tech.history.HistoryStore;
import org.gamboni.tech.web.js.JavaScript.JsExpression;

import java.io.File;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.Executor;
import java.util.concurrent.LinkedBlockingDeque;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;

import static com.google.common.base.Preconditions.checkNotNull;
import static org.gamboni.tech.web.js.JavaScript.literal;

/**
 * @author tendays
 *
 */
public class MServerController extends AbstractController {

	/* Design notes: also refer to `MpvMediaPlayer`.
	 * The controller is responsible for handling the play queue, and acting
	 * as intermediary between the front end and the media player.
	 * As it sits between two event sources, care must be taken with concurrency.
	 * Using synchronized methods would create deadlocks (if sending a request to play
	 * a file and blocking while waiting for the response, while the media player
	 * is trying to notify this of a state change). So we follow the idea of treating
	 * the controller like the browser, with an event queue (implemented with a
	 * single-threaded executor). Then events originating from the media player
	 * are never blocking, they are just added to the end of the event queue.
	 * (The event queue (which should be processed as fast as the CPU permits)
	 * should not be confused with the play queue.)
	 */

	final File root;
	private final MServerSocket socketHandler;
	private final MediaPlayer mediaPlayer;

	private final Executor executor = new ThreadPoolExecutor(
			1,
			1, // important: to make this a *sequential* executor
			Long.MAX_VALUE,
			TimeUnit.SECONDS,
			new LinkedBlockingDeque<>());

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
		pause();
		return "ok";
	});

	public final JsExpression skip;

	public final JsExpression stop = service("stop", () -> {
		stop();
		return "ok";
	});

	public MServerController(Mapping mapping, MServerSocket socketHandler, File folder, List<String> extraPlayerArgs) {
		super(mapping);
		this.mediaPlayer = new MpvMediaPlayer(mapping, extraPlayerArgs);
		this.store = new MServerHistoryStore(mapping);
		this.socketHandler = socketHandler;
		this.root = folder;
		mediaPlayer.setChangeListener(new MediaPlayer.ChangeListener() {
			@Override
			public void stopped() {
				// TODO it's easy to forget the executor.execute. Should somehow expose sensitive variables/methods only to executable tasks
				// this could be done by wrapping them together with the executor.
				executor.execute(() -> {
					if (!queue.isEmpty()) {
						var item = queue.remove(0);
						mediaPlayer.playIfIdle(item.file, () -> {
							// oops: apparently the player started playing something already:
							// let's put the file back into the queue
							queue.add(0, item);
						});
					} else {
						shuffleOne().ifPresentOrElse(file ->
								mediaPlayer.playIfIdle(file,
										() -> {
											// ignore: if player is already playing something
											// we don't need to add shuffled items
										}), () ->
								// nothing to shuffle: let's publish the "STOPPED" event downstream
								updateStore(s -> {
									s.setGlobalState(GlobalState.STOPPED);
									Optional<File> playingBefore = s.getNowPlaying();
									s.clearNowPlaying();
									// see if we need to remove "playing" status of oldState
									playingBefore.ifPresent(toRemove ->
											s.setFileState(ancestors(toRemove),
													pointer -> isQueued(pointer) ? PlayState.QUEUED : PlayState.STOPPED));
								}));
					}
				});
			}

			@Override
			public void playing(Instant started, double duration) {
				executor.execute(() -> {
					updateStore(s -> s.setGlobalState(new PlayingGlobalState(
							started,
							duration)));
				});
			}

			@Override
			public void paused(double position) {
				updateStore(s -> {
					if (s.getGlobalState() instanceof PlayingGlobalState gs) {
						s.setGlobalState(
								new PausedGlobalState(position, gs.duration()));
					} else {
						throw new IllegalStateException(s.getGlobalState().toString()); // not nice
					}
				});
			}
		});
		socketHandler.setController(this);

		this.play = service("play", fileName -> {
			executor.execute(() -> {
				Item item = new Item(mapping.pathToFile(fileName));

				playNow(item, () -> {
					queue.add(item);
					System.out.println("Queued " + item);
					var notifications = store.update(session -> {
						session.setFileState(ancestors(item.file),
								pointer -> store.isNowPlaying(pointer) ?
										DirectoryPage.PLAY_STATE_FUNCTION.apply(
												store.getGlobalState().getClass()) : PlayState.QUEUED);
					});

					broadcastState(notifications);
				});
			});
			return "ok";
		});

		this.skip = service("skip", () -> {
			mediaPlayer.stop();
			return "ok";
		});

		this.shuffle = service("shuffle", folderName -> {
			executor.execute(() -> {
				if (shuffleFolder.isPresent()) {
					shuffleFolder = Optional.empty();
					stop();
				} else {
					this.shuffleFolder = Optional.of(mapping.pathToFile(folderName));
					shuffleOne().ifPresent(f -> {
						playNow(new Item(f),
								() -> stop());
					});
				}
			});
			return "ok";
		});
	}

	private void pause() {
		mediaPlayer.togglePaused();
	}

	private void stop() {
		executor.execute(() -> {
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
			mediaPlayer.stop();
		});
	}

	private Optional<File> shuffleOne() {
		return shuffleFolder.flatMap(f -> {
			File[] files = f.listFiles(file -> new Item(file).isMusic());
			if (files == null || files.length == 0) {
				return Optional.empty();
			} else {
				return Optional.of(files[(int) (Math.random() * files.length)]);
			}
		});
	}

	private void playNow(Item item, Runnable queue) {
		mediaPlayer.playIfIdle(item.file, queue);

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
