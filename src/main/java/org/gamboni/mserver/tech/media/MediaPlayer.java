package org.gamboni.mserver.tech.media;

import java.io.File;
import java.time.Instant;

public interface MediaPlayer {
    void togglePaused();

    void stop();

    void playIfIdle(File file, Runnable otherwise);

    interface ChangeListener {
        public static final ChangeListener NOOP = new ChangeListener() {
            @Override
            public void stopped() {}

            @Override
            public void playing(Instant started, double duration) {}

            @Override
            public void paused(double position) {}
        };
        void stopped();
        void playing(Instant started, double duration);
        void paused(double position);
    }

    void setChangeListener(ChangeListener listener);
}
