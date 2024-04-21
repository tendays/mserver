package org.gamboni.mserver;

import lombok.extern.slf4j.Slf4j;
import org.eclipse.jetty.websocket.api.Session;
import org.eclipse.jetty.websocket.api.annotations.OnWebSocketClose;
import org.eclipse.jetty.websocket.api.annotations.OnWebSocketConnect;
import org.eclipse.jetty.websocket.api.annotations.OnWebSocketMessage;
import org.eclipse.jetty.websocket.api.annotations.WebSocket;
import spark.Spark;

import java.util.HashSet;
import java.util.Set;

@WebSocket
@Slf4j
public class MServerSocket {
    private Set<Session> sessions = new HashSet<>();
    public static final String PATH = "/ws";

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
    public void onMessage(Session session, String message) {
            log.info("Received '{}' from {}", message, session);
    }
}
