package org.gamboni.mserver;

import org.gamboni.mserver.data.Item;
import org.gamboni.mserver.data.PlayState;
import org.gamboni.tech.history.Stamped;

import java.io.File;
import java.util.Map;

public record DirectorySnapshot(long stamp, Map<File, PlayState> states) implements Stamped {
    public PlayState getFileState(File file) {
        return states.getOrDefault(file, PlayState.STOPPED);
    }

    public record ItemSnapshot(Item item, PlayState state) {
        public boolean isDirectory() {
            return item.isDirectory();
        }

        public String name() {
            return item.name;
        }

        public String friendlyName() {
            return item.friendlyName();
        }

        public File file() {
            return item.file;
        }
    }

    public ItemSnapshot getItem(File file) {
        return new ItemSnapshot(new Item(file), getFileState(file));
    }
}
