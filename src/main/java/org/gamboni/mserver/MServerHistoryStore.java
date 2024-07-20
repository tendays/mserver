package org.gamboni.mserver;

import org.gamboni.mserver.data.*;
import org.gamboni.tech.history.InMemoryHistoryStore;
import org.gamboni.tech.web.ws.BroadcastTarget;

import java.io.File;
import java.util.*;
import java.util.function.Function;

import static java.util.stream.Collectors.toSet;

public class MServerHistoryStore extends InMemoryHistoryStore<
        File,
        DirectorySnapshot,
        MServerHistoryStore.UpdateSession,
        MServerEvent> {

    private final Map<File, DirectoryState> directoryStates = new HashMap<>();

    private Optional<File> nowPlaying = Optional.empty();
    private volatile GlobalState globalState = GlobalState.STOPPED;

    private synchronized DirectoryState directoryState(File path) {
        return directoryStates.computeIfAbsent(path, __ -> new DirectoryState());
    }

    @Override
    public synchronized DirectorySnapshot getSnapshot(File path) {
        return new DirectorySnapshot(getStamp(), directoryState(path).getSnapshot());
    }

    @Override
    public synchronized List<MServerEvent> internalAddListener(BroadcastTarget client, File path, long stamp) {
        DirectoryState directoryState = directoryState(path);
        directoryState.addListener(client);
        List<MServerEvent> events = new ArrayList<>(directoryState.getUpdatesSince(stamp));
        events.add(this.globalState);
        return events;
    }

    public GlobalState getGlobalState() {
        return globalState;
    }

    public synchronized boolean isNowPlaying(File path) {
        return nowPlaying.map(playingPath -> playingPath
                .getPath().startsWith(path.getPath()))
                .orElse(false);
    }

    public class UpdateSession extends AbstractUpdateSession<MServerEvent> {

        private UpdateSession(long stamp) {
            super(stamp);
        }

        public void setNowPlaying(File file) {
            MServerHistoryStore.this.nowPlaying = Optional.of(file);
        }
        public void clearNowPlaying() {
            MServerHistoryStore.this.nowPlaying = Optional.empty();
        }

        public void setGlobalState(GlobalState globalState) {
            boolean significant =
                    MServerHistoryStore.this.globalState.state() != globalState.state() ||
                            // when starting a new item duration switches from 0 to actual value;
                            // we want to notify the front ends at that time
                            MServerHistoryStore.this.globalState.duration() != globalState.duration();

            MServerHistoryStore.this.globalState = globalState;
            if (significant) {
                clients().forEach(client -> notifications.put(client, globalState));
            }
        }

        public void setFileState(Iterable<File> files, Function<File, PlayState> stateFunction) {
            for (var file : files) {
                var state = stateFunction.apply(file);
                FileState fileState = new FileState(file, state);
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
