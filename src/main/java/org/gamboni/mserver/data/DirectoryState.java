package org.gamboni.mserver.data;

import org.gamboni.tech.web.ws.BroadcastTarget;

import java.io.File;
import java.util.*;

import static java.util.stream.Collectors.toMap;

public class DirectoryState {

    private final Set<BroadcastTarget> listeners = new LinkedHashSet<>();

    public Set<BroadcastTarget> getClients() {
        return Collections.unmodifiableSet(listeners);
    }

    private record StampedState(PlayState state, long stamp) {}

    private final Map<File, StampedState> fileState = new HashMap<>();

    /** Update the play-state of a file.
     *
     * @param file the file whose state changed
     * @param stamp the epoch (to allow temporarily-disconnected clients to catch up later)
     * @param state the new state of the file
     * @return the list of clients to notify of this change
     */
    public List<BroadcastTarget> setFileState(File file, long stamp, PlayState state) {
        fileState.put(file, new StampedState(state, stamp));
        return List.copyOf(listeners);
    }

    public void addListener(BroadcastTarget listener) {
        this.listeners.add(listener);
    }

    public void removeListener(BroadcastTarget listener) {
        this.listeners.remove(listener);
    }

    public Map<File, PlayState> getSnapshot() {
        return fileState.entrySet().stream()
                .collect(toMap(Map.Entry::getKey,
                        e -> e.getValue().state()));
    }

    public List<FileState> getUpdatesSince(long stamp) {
        return fileState
                .entrySet()
                .stream()
                .filter(e -> e.getValue().stamp > stamp)
                .map(e -> new FileState(e.getKey(), e.getValue().state()))
                .toList();
    }
}
