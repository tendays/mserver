package org.gamboni.mserver.data;

public enum PlayState {
    STOPPED,
    PLAYING,
    QUEUED,
    PAUSED;

    public PlayState togglePaused() {
        return switch (this) {
            case PAUSED -> PLAYING;
            case PLAYING -> PAUSED;
            default -> this;
        };
    }
}
