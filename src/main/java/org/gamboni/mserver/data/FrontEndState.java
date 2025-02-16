package org.gamboni.mserver.data;

import org.gamboni.tech.web.js.JS;

/**
 * @param state Global player state.
 * @param pausedPosition When state is {@link PlayState#PAUSED}, holds the current position in the file.
 * @param playStarted When the state is {@link PlayState#PLAYING}, holds a simulated start time.
 *          * To calculate the current position in the file,
 *          * pretend playback started at that time and proceeded without
 *          * pause. (Fake start time may differ from true start time in case
 *          * playback was paused or playback speed is not 1:1).
 * @param duration Total duration of the current stream, Zero if the player is {@link PlayState#STOPPED}.
 */
@JS
public record FrontEndState (
        PlayState state,
        long pausedPosition,
        long playStarted,
        long duration
) {
}
