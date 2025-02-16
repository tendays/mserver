package org.gamboni.mserver;

import lombok.Getter;
import lombok.RequiredArgsConstructor;
import org.gamboni.mserver.data.DirectoryState;
import org.gamboni.mserver.data.GlobalState;
import org.gamboni.mserver.data.PlayState;
import org.gamboni.mserver.tech.Mapping;
import org.gamboni.tech.history.InMemoryHistoryStore;
import org.gamboni.tech.history.event.Event;
import org.gamboni.tech.history.event.NewStateEvent;
import org.gamboni.tech.web.ws.BroadcastTarget;

import java.io.File;
import java.util.*;
import java.util.function.Function;

import static java.util.stream.Collectors.toSet;

@RequiredArgsConstructor
public class MServerHistoryStore extends InMemoryHistoryStore<
        File,
        DirectorySnapshot,
        MServerHistoryStore.UpdateSession> {

    private final Mapping mapping;
    private final Map<File, DirectoryState> directoryStates = new HashMap<>();
    private final Map<BroadcastTarget, DirectoryState> listeners = new HashMap<>();

    private Optional<File> nowPlaying = Optional.empty();
    @Getter
    private volatile GlobalState globalState = GlobalState.STOPPED;

    private synchronized DirectoryState directoryState(File path) {
        return directoryStates.computeIfAbsent(path, __ -> new DirectoryState(this.mapping));
    }

    @Override
    public synchronized DirectorySnapshot getSnapshot(File path) {
        return new DirectorySnapshot(getStamp(), directoryState(path).getSnapshot());
    }

    @Override
    public synchronized List<Event> internalAddListener(BroadcastTarget client, File path, long stamp) {
        DirectoryState directoryState = directoryState(path);
        directoryState.addListener(client);
        listeners.put(client, directoryState);
        List<Event> events = new ArrayList<>(directoryState.getUpdatesSince(stamp));
        // Note: these could be conditional on 'stamp' as well, at least the first one…
        events.add(globalState);
        return events;
    }

    @Override
    public void removeListener(BroadcastTarget broadcastTarget) {
        listeners.get(broadcastTarget).removeListener(broadcastTarget);
    }

    public synchronized boolean isNowPlaying(File path) {
        return nowPlaying.map(playingPath -> playingPath
                .getPath().startsWith(path.getPath()))
                .orElse(false);
    }

    public class UpdateSession extends AbstractUpdateSession {

        private UpdateSession(long stamp) {
            super(stamp);
        }

        public void setNowPlaying(File file) {
            MServerHistoryStore.this.nowPlaying = Optional.of(file);
        }
        public void clearNowPlaying() {
            MServerHistoryStore.this.nowPlaying = Optional.empty();
        }

        public void setGlobalState(GlobalState newState) {
            if (globalState.state() != newState.state() ||

            // when starting a new item duration switches from 0 to actual value;
            // we want to notify the front ends at that time
            globalState.duration() != newState.duration()) {
                clients().forEach(client -> notifications.put(client, newState));
            }

            MServerHistoryStore.this.globalState = newState;
        }

        public void setFileState(Iterable<File> files, Function<File, PlayState> stateFunction) {
            for (var file : files) {
                var state = stateFunction.apply(file);
                NewStateEvent<PlayState> fileState = new NewStateEvent<>("", mapping.fileToPath(file), state);
                for (var client : directoryState(file.getParentFile()).setFileState(file, getStamp(), state)) {
                    notifications.put(client, fileState);
                }
            }
        }

        public void setFileState(Iterable<File> files, PlayState state) {
            setFileState(files, __ -> state);
        }

        public GlobalState getGlobalState() {
            return globalState;
        }

        public Optional<File> getNowPlaying() {
            return MServerHistoryStore.this.nowPlaying;
        }
    }

    private Set<BroadcastTarget> clients() {
        return directoryStates.values()
                .stream()
                .map(DirectoryState::getClients)
                .flatMap(Collection::stream)
                .collect(toSet());
    }

    @Override
    protected UpdateSession newTransaction(long stamp) {
        return new UpdateSession(stamp);
    }
}
