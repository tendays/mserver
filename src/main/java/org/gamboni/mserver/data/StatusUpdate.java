package org.gamboni.mserver.data;

import org.gamboni.tech.web.js.JS;

import java.util.List;

@JS
public record StatusUpdate(
        /** Global play state (whether something somewhere is playing) */
        PlayState state,
        /** Position in the currently-playing file, if any */
        double position,
        /** Duration of the currently-playing file, if any */
        double duration,
        /** Formatted position in the current file. */
        String time,
        /** File states to update in the front end */
        List<FileState> files) {

    public static StatusUpdate forStatus(Status status, List<FileState> fileChanges) {
        return new StatusUpdate(
                status.state(),
                status.position(),
                status.duration(),
                status.time(),
                fileChanges);
    }
}
