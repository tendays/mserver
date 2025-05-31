package org.gamboni.mserver.tech.media;

import com.google.common.collect.ImmutableList;
import com.google.common.util.concurrent.Uninterruptibles;
import lombok.Setter;
import lombok.ToString;
import org.gamboni.mserver.tech.Mapping;

import java.io.BufferedReader;
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

    private Optional<Process> mpvProcess = Optional.empty();
    private Optional<SocketClient> mpvClient = Optional.empty();

    @Setter
    private ChangeListener changeListener = ChangeListener.NOOP;

    public MpvMediaPlayer(Mapping mapping, List<String> extraPlayerArgs) {
        this.mapping = mapping;
        this.extraPlayerArgs = ImmutableList.copyOf(extraPlayerArgs);
    }

    @Override
    public void togglePaused() {
        ifRunning(mp -> {
            mp.write("{\"command\": [\"cycle\", \"pause\"]}");
        });
    }

    @Override
    public void stop() {
        ifRunning(mp -> {
            mp.write("{\"command\": [\"quit\"]}");
        });
    }

    @Override
    public synchronized void playIfIdle(File file, Runnable otherwise) {
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
                this.mpvProcess = Optional.of(Runtime.getRuntime().exec(
                        commandLine
                                .toArray(new String[0])));
            } catch (IOException e) {
                throw new RuntimeException("Error starting MPV", e);
            }
        }
    }

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
        SocketChannel mpvSocket = openMpvSocketConnection();
        BufferedReader reader = openReader(mpvSocket);
        long nextRequestId = 1;
        Long durationProperty;
        Long positionProperty;
        double duration = 0;
        double position = 0;

        void init() throws IOException {
            duration = 0;
            position = 0;

            // Duration is not supposed to change, but I've sometimes seen it being null when querying too soon,
            // so we "observe" it to be sure we eventually get the correct value.
            durationProperty = (nextRequestId++);
            write("{\"command\":[\"observe_property_string\", "+ durationProperty +", \"duration\"]}");

            positionProperty = (nextRequestId++);
            write("{\"command\":[\"observe_property_string\", "+ positionProperty +", \"playback-time\"]}");
        }

        void poll() throws IOException {
            String line = null;
            try {
                line = reader.readLine(); // blocking
            } catch (IOException ioX) {
                System.err.println(ioX.getMessage());
                // likely MPV terminated, let's reconnect
            }
            if (line == null || !mpvProcess.get().isAlive()) {
                mpvProcess = Optional.empty();
                changeListener.stopped();
                /*if (pop()) {
                    reconnect();
                }*/
                return;
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
                duration =  (message.data == null) ? 0 : Double.parseDouble(message.data) * 1000;
            } else {
                System.err.println(line);
                return;
            }

            changeListener.playing(
                            Instant.now().minusMillis((long) position),
                            duration);

        }

        void close() {
            tryClose(mpvSocket);
            mpvSocket = null;
            reader = null;
        }

        void reconnect() throws IOException {
            RuntimeException lastError = null;
            int tries = 0;
            while (tries < 5) {
                try {
                    close();

                    mpvSocket = openMpvSocketConnection();
                    reader = openReader(mpvSocket);
                    init();
                    return;
                } catch (IOException e) {
                    tries++;
                    lastError = new RuntimeException(e);
                    System.err.println("Socket re-connection attempt " + tries + " failed with " + e);
                    Uninterruptibles.sleepUninterruptibly(Duration.ofMillis(500));
                }
            }
            System.err.println("Giving up re-connection after " + tries + " attempts");
            throw lastError;
        }

        private void write(String string) throws IOException {
            System.err.println("> "+ string);
            mpvSocket.write(ByteBuffer.wrap((string + "\n").getBytes(StandardCharsets.UTF_8)));
        }
    }

    private static SocketChannel openMpvSocketConnection() {
        RuntimeException lastError = null;
        int tries = 0;
        while (tries < 5) {
            try {
                return SocketChannel.open(UnixDomainSocketAddress.of(SOCKET));
            } catch (IOException e) {
                tries++;
                lastError = new RuntimeException(e);
                System.err.println("Socket connection attempt " + tries +" failed with "+ e);
                Uninterruptibles.sleepUninterruptibly(Duration.ofMillis(500));
            }
        }
        System.err.println("Giving up connection after "+ tries +" attempts");
        throw lastError;
    }

    private void startStatusThread() {
        new Thread(() -> {
            SocketClient client = new SocketClient();
            try {
                client.init();
                mpvClient = Optional.of(client);
                while (isRunning()) {
                    client.poll();
                }
            } catch (IOException e) {
                e.printStackTrace();
            } finally {
                client.close();
                mpvClient = Optional.empty();
            }
        }).start();
    }

    private synchronized boolean isRunning() {
        return mpvProcess.isPresent();
    }

    private interface ThrowingProcessConsumer {
        void accept(SocketClient socketClient) throws IOException;
    }

    /**
     * Pass the MPV Process, if available, to the given consumer.
     */
    private void ifRunning(ThrowingProcessConsumer consumer) {
        Optional<SocketClient> copy;
        synchronized (this) {
            copy = this.mpvClient;
        }
        if (copy.isPresent()) {
            try {
                consumer.accept(copy.get());
            } catch (IOException e) {
                // Would this happen if mpv has just finished? In that case maybe we should resend the command
                // To the next instance from the queue (if any)? E.g. clicking "pause" or "stop" between two runs
                // should probably pause/stop the newly started instance instead of crashing / doing nothing.
                throw new RuntimeException();
            }
        }
    }

    private static BufferedReader openReader(SocketChannel channel) {
        return new BufferedReader(Channels.newReader(channel, StandardCharsets.UTF_8));
    }

    private void tryClose(AutoCloseable r) {
        try {
            if (r != null) {
                r.close();
            }
        } catch (Exception e) {
            e.printStackTrace();
        }
    }
}
