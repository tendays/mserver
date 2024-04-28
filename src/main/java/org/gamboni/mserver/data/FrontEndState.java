package org.gamboni.mserver.data;

import org.gamboni.tech.web.js.JS;

@JS
public record FrontEndState (
        PlayState state,

        /** When state is "paused", holds the current position in the file. */
        long pausedPosition,
        /** When the state is "playing", holds a simulated start time.
         * To calculate the current position in the file,
         * pretend playback started at that time and proceeded without
         * pause. (Fake start time may differ from true start time in case
         * playback was paused or playback speed is not 1:1).
         */
        long playStarted,

        /** Total duration of the current stream. */
        long duration
) {
}
