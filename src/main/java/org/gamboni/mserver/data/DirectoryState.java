package org.gamboni.mserver.data;

import lombok.Getter;
import org.gamboni.tech.sparkjava.SparkWebSocket;

import java.io.File;
import java.util.*;

public class DirectoryState {

    private final Set<SparkWebSocket.Client> listeners = new LinkedHashSet<>();
    @Getter
    private int stamp;

    private record StampedState(PlayState state, int stamp) {}

    private final Map<File, StampedState> fileState = new HashMap<>();

    /** Update the play-state of a file.
     *
     * @param file the file whose state changed
     * @param stamp the epoch (to allow temporarily-disconnected clients to catch up later)
     * @param state the new state of the file
     * @return the list of clients to notify of this change
     */
    public synchronized List<SparkWebSocket.Client> setFileState(File file, int stamp, PlayState state) {
        this.stamp = stamp;
        fileState.put(file, new StampedState(state, stamp));
        return List.copyOf(listeners); // TODO deal with concurrent changes: client arriving after this copy, and client going away after this copy? Use stamps.
    }

    public synchronized void addListener(SparkWebSocket.Client listener) {
        this.listeners.add(listener);
    }

    public synchronized PlayState getFileState(File file) {
        return Optional.ofNullable(fileState.get(file))
                .map(StampedState::state)
                .orElse(PlayState.STOPPED);
    }

    public synchronized List<FileState> getUpdatesSince(int stamp) {
        return fileState
                .entrySet()
                .stream()
                .filter(e -> e.getValue().stamp > stamp)
                .map(e -> new FileState(e.getKey(), e.getValue().state()))
                .toList();
    }
}
