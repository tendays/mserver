package org.gamboni.mserver.data;

import org.gamboni.tech.history.event.Event;
import org.gamboni.tech.web.js.JS;

@JS
public record GlobalState(
        /** Global play state (whether something somewhere is playing) */
        PlayState state,
        double position,
        double duration) implements Event {
    public static final GlobalState STOPPED = new GlobalState(
            PlayState.STOPPED,
            0,
            0
    );
    public static final GlobalState PLAYING = new GlobalState(
            PlayState.PLAYING,
            0,
            0
    );

    public GlobalState togglePaused() {
        return new GlobalState(
                this.state.togglePaused(),
                this.position,
                this.duration
        );
    }
}
