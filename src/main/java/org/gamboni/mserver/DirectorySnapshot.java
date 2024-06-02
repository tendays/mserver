package org.gamboni.mserver;

import org.gamboni.mserver.data.PlayState;
import org.gamboni.tech.history.Stamped;

import java.io.File;
import java.util.Map;

public record DirectorySnapshot(long stamp, Map<File, PlayState> states) implements Stamped {
    public PlayState getFileState(File file) {
        return states.getOrDefault(file, PlayState.STOPPED);
    }
}
