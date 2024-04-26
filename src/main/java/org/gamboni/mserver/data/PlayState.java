package org.gamboni.mserver.data;

public enum PlayState {
    UNKNOWN,
    STOPPED,
    PLAYING,
    PAUSED;

    public PlayState togglePaused() {
        return switch (this) {
            case PAUSED -> PLAYING;
            case PLAYING -> PAUSED;
            default -> this;
        };
    }
}
