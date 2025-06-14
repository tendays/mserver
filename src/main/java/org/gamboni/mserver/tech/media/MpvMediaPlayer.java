package org.gamboni.mserver.tech.media;

import com.google.common.collect.ImmutableList;
import com.google.common.util.concurrent.Uninterruptibles;
import lombok.Setter;
import lombok.ToString;
import org.gamboni.mserver.tech.Mapping;

import java.io.BufferedReader;
import java.io.Closeable;
import java.io.File;
import java.io.IOException;
import java.net.UnixDomainSocketAddress;
import java.nio.ByteBuffer;
import java.nio.channels.Channels;
import java.nio.channels.SocketChannel;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

/** MPV support. */
public class MpvMediaPlayer implements MediaPlayer {
    /* Design notes:
     * We treat mpv like a remote system, much like the browser sees the back end.
     * however, the media player has no concept of queue. It is either playing/paused
     * a file, or not playing.
     * The controller (MServerController) sends *commands* such as "play a file" (which is only successful
     * if the media player is idle at the time it processes the command), "pause"
     * or "stop". Inversely, the media player sends *events* to the controller, such as
     * "playing started (which includes total duration information)", "playback paused/resumed",
     * or "playback stopped" (either interrupted or reaching the end.
     */
    private static final String SOCKET = "/tmp/mserver";

    private final List<String> extraPlayerArgs;
    private final Mapping mapping;

    private volatile Optional<SocketClient> mpvClient = Optional.empty();

    @Setter
    private ChangeListener changeListener = ChangeListener.NOOP;

    public MpvMediaPlayer(Mapping mapping, List<String> extraPlayerArgs) {
        this.mapping = mapping;
        this.extraPlayerArgs = ImmutableList.copyOf(extraPlayerArgs);
    }

    @Override
    public void togglePaused() {
        trySendMessage("{\"command\": [\"cycle\", \"pause\"]}");
    }

    @Override
    public void stop() {
        trySendMessage("{\"command\": [\"quit\"]}");
    }

    @Override
    public synchronized void playIfIdle(File file, Runnable playingStarted, Runnable otherwise) {
        /* Method is synchronized to prevent starting two players at the same time. */
        if (isRunning()) {
            otherwise.run();
        } else {
            ImmutableList<String> commandLine = ImmutableList.<String>builder()
                    .add("mpv", "--input-ipc-server=" + SOCKET, "--vo=null")
                    .addAll(extraPlayerArgs)
                    .add(file.getPath())
                    .build();

            System.err.println("$ " + String.join(" ", commandLine));

            try {
                startStatusThread(Runtime.getRuntime().exec(
                        commandLine.toArray(new String[0])));
            } catch (IOException e) {
                throw new RuntimeException("Error starting MPV", e);
            }
            playingStarted.run();
        }
    }

    /** Event object published by mpv. */
    @ToString
    static class MpvMessage {
        public String event;
        public Long id;
        public String name;
        public String data;
        public String error;
        public Long request_id;
    }

    private class SocketClient {
        final Process process;

        SocketChannel mpvSocket;
        BufferedReader reader;

        long nextRequestId = 1;
        Long durationProperty;
        Long positionProperty;
        Long pausedProperty;
        double duration = 0;
        double position = 0;
        boolean paused = false;

        private SocketClient(Process process) {
            this.process = process;
        }

        private boolean isConnected() {
            return reader != null;
        }

        /** Try connecting to the MPV socket. This command may only be used when we're not connected. */
        public boolean tryConnecting() {
            try {
                this.mpvSocket = openMpvSocketConnection();
                this.reader = openReader(mpvSocket);
                init(); // if above was successful…
                return true;
            } catch (IOException e) {
                return false;
            }
        }

        private void init() throws IOException {
            // Duration is not supposed to change, but I've sometimes seen it being null when querying too soon,
            // so we "observe" it to be sure we eventually get the correct value.
            durationProperty = (nextRequestId++);
            write("{\"command\":[\"observe_property_string\", "+ durationProperty +", \"duration\"]}");

            positionProperty = (nextRequestId++);
            write("{\"command\":[\"observe_property_string\", "+ positionProperty +", \"playback-time\"]}");

            pausedProperty = (nextRequestId++);
            write("{\"command\":[\"observe_property_string\", "+ pausedProperty +", \"pause\"]}");
        }

        /** Wait for the next event coming from MPV. This command may only be used when the client is connected. */
        public boolean poll() throws IOException {
            String line = null;
            try {
                line = reader.readLine(); // blocking
            } catch (IOException ioX) {
                System.err.println(ioX.getMessage());
            }
            if (line == null) {
                synchronized (this) { // required before clearing 'reader' to avoid race with write()
                    mpvSocket = tryClose(mpvSocket);
                    reader = tryClose(reader);
                }
                return false;
            }
					/* example messages:

					{"event":"property-change","id":1,"name":"playback-time","data":39.040266}
					{"request_id":0,"error":"invalid parameter"}
					{"data":"374.955828","request_id":12,"error":"success"}
					 */
            var message = mapping.readValue(line, MpvMessage.class);
            if (positionProperty.equals(message.id)) {
                position = (message.data == null) ? 0 : Double.parseDouble(message.data) * 1000;
            } else if (durationProperty.equals(message.id)) {
                duration = (message.data == null) ? 0 : Double.parseDouble(message.data) * 1000;
            } else if (pausedProperty.equals(message.id)) {
                paused = message.data.equals("yes");
            } else {
                System.err.println(line);
                return true;
            }

            if (paused) {
                changeListener.paused(position);
            } else {
                changeListener.playing(
                        Instant.now().minusMillis((long) position),
                        duration);
            }

            return true;
        }

        void close() {
            tryClose(mpvSocket);
            mpvSocket = null;
            reader = null;
        }

        private synchronized void write(String string) throws IOException {
            SocketChannel copy = mpvSocket;
            if (copy != null) {
                System.err.println("> " + string);
                mpvSocket.write(ByteBuffer.wrap((string + "\n").getBytes(StandardCharsets.UTF_8)));
            }
        }
    }

    private static SocketChannel openMpvSocketConnection() throws IOException {
        return SocketChannel.open(UnixDomainSocketAddress.of(SOCKET));
    }

    private void startStatusThread(Process mpvProcess) {
        var client = new SocketClient(mpvProcess);
        this.mpvClient = Optional.of(client);
        new Thread(() -> {
            try {
                client.init();
                while (mpvProcess.isAlive()) {
                    boolean ok;
                    if (client.isConnected()) {
                        ok = client.poll();
                    } else {
                        ok = client.tryConnecting();
                    }
                    if (!ok) {
                        // either lost connection (probably mpv is shutting down)
                        // or could not connect (probably mpv is starting up): wait a bit and try again
                        Uninterruptibles.sleepUninterruptibly(Duration.ofMillis(500));
                    }
                }
            } catch (IOException e) {
                e.printStackTrace();
            } finally {
                client.close();
                mpvClient = Optional.empty();
                changeListener.stopped();
            }
        }).start();
    }

    private boolean isRunning() {
        return mpvClient.isPresent();
    }

    /**
     * Pass the MPV Process, if available, to the given consumer.
     */
    private void trySendMessage(String message) {
        Optional<SocketClient> copy = this.mpvClient;
        if (copy.isPresent()) {
            try {
                copy.get().write(message);
            } catch (IOException e) {
                // Would this happen if mpv has just finished? In that case maybe we should resend the command
                // To the next instance from the queue (if any)? E.g. clicking "pause" between two runs
                // should probably pause the newly started instance instead of crashing / doing nothing.
            }
        }
    }

    private static BufferedReader openReader(SocketChannel channel) {
        return new BufferedReader(Channels.newReader(channel, StandardCharsets.UTF_8));
    }

    private static <T extends Closeable> T tryClose(T resource) {
        try {
            if (resource != null) {
                resource.close();
            } // else: resource was already closed, so do nothing
        } catch (IOException e) {
            // ignore
        }
        return null;
    }

}
