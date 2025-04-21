package org.gamboni.mserver.data;

import org.gamboni.tech.web.js.JS;

import java.time.Instant;

@JS
public record PlayingGlobalState(
        Instant started,
        double duration) implements GlobalState {
}
