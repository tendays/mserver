package org.gamboni.mserver;

import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.Setter;
import lombok.extern.slf4j.Slf4j;
import org.eclipse.jetty.websocket.api.Session;
import org.eclipse.jetty.websocket.api.annotations.OnWebSocketClose;
import org.eclipse.jetty.websocket.api.annotations.OnWebSocketConnect;
import org.eclipse.jetty.websocket.api.annotations.OnWebSocketMessage;
import org.eclipse.jetty.websocket.api.annotations.WebSocket;
import org.gamboni.mserver.data.Status;
import spark.Spark;

import java.io.IOException;
import java.util.HashSet;
import java.util.Set;

@WebSocket
@Slf4j
public class MServerSocket {
    public static final String PATH = "/ws";
    public static final String GET_STATUS = "getStatus";

    @Setter
    private MServerController controller;
    private Set<Session> sessions = new HashSet<>();

    private ObjectMapper mapper = new ObjectMapper();

    public MServerSocket() {
        Spark.webSocket(MServerSocket.PATH, this);
    }

    @OnWebSocketConnect
    public synchronized void onConnect(Session session) throws Exception {
        log.info("New connection {}", session);
        sessions.add(session);
    }

    @OnWebSocketClose
    public synchronized void onClose(Session session, int statusCode, String reason) {
        sessions.remove(session);
        log.info("Connection {} terminated: {} {} ", session, statusCode, reason);
    }

    @OnWebSocketMessage
    public void onMessage(Session session, String message) throws IOException {
        log.info("Received '{}' from {}", message, session);
        if (message.equals(GET_STATUS)) {
            session.getRemote().sendString(mapper.writeValueAsString(controller.getCurrentStatus()));
        }
    }

    public void broadcastState(Status newState) {
        Set<Session> sessionCopy;
        synchronized (this) {
            sessionCopy = Set.copyOf(this.sessions);
        }
        log.info("Broadcasting {} to {} sessions.", newState, sessionCopy.size());
        for (var session : sessionCopy) {
            if (session.isOpen()) {
                try {
                    session.getRemote().sendString(mapper.writeValueAsString(newState));
                } catch (IOException e) {
                    log.warn("Failed sending status update to {}", session, e);
                }
            }
        }
    }
}
