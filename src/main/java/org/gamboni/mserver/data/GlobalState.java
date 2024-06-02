package org.gamboni.mserver.data;

import org.gamboni.tech.web.js.JS;

@JS
public record GlobalState(
        /** Global play state (whether something somewhere is playing) */
        PlayState state,
        /** Position in the currently-playing file, if any */
        double position,
        /** Duration of the currently-playing file, if any */
        double duration,
        /** Formatted position in the current file. */
        String time) implements MServerEvent {
    public static final GlobalState STOPPED = new GlobalState(
            PlayState.STOPPED,
            0,
            0,
            ""
    );
    public static final GlobalState PLAYING = new GlobalState(
            PlayState.PLAYING, 0, 0, "00:00.0"
    );

    public GlobalState togglePaused() {
        return new GlobalState(
                this.state.togglePaused(),
                this.position,
                this.duration,
                this.time
        );
    }
}
