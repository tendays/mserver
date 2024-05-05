package org.gamboni.mserver;

import lombok.Setter;
import lombok.extern.slf4j.Slf4j;
import org.eclipse.jetty.websocket.api.annotations.WebSocket;
import org.gamboni.mserver.data.Status;
import org.gamboni.tech.sparkjava.SparkWebSocket;

@WebSocket
@Slf4j
public class MServerSocket extends SparkWebSocket {
    public static final String GET_STATUS = "getStatus";

    @Setter
    private MServerController controller;

    public MServerSocket() {
        init();
    }

    public void broadcastState(Status newState) {
        broadcast(newState);
    }

    @Override
    protected boolean handleMessage(Client client, String message) {
        if (message.equals(GET_STATUS)) {
            client.sendOrLog(controller.getCurrentStatus());
            return true;
        } else {
            return false;
        }
    }
}