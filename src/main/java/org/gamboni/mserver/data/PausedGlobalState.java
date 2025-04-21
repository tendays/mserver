package org.gamboni.mserver.data;

import org.gamboni.tech.web.js.JS;

@JS
public record PausedGlobalState(double position, double duration) implements GlobalState {
}
