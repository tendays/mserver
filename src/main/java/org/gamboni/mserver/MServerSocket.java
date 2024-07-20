package org.gamboni.mserver;

import lombok.Setter;
import lombok.extern.slf4j.Slf4j;
import org.eclipse.jetty.websocket.api.annotations.WebSocket;
import org.gamboni.mserver.data.GetStatus;
import org.gamboni.mserver.tech.Mapping;
import org.gamboni.tech.sparkjava.SparkWebSocket;
import org.gamboni.tech.web.ws.BroadcastTarget;

@WebSocket
@Slf4j
public class MServerSocket extends SparkWebSocket {

    @Setter
    private MServerController controller;

    private final Mapping mapping;

    public MServerSocket(Mapping mapping) {
        super(mapping);

        // Yes, I know, I should set up some real dependency management
        this.mapping = mapping;
        init();
    }

    @Override
    protected boolean handleMessage(BroadcastTarget client, String message) {
        var getStatus = mapping.readValue(message, GetStatus.class);
        var updates = controller.getStore()
                .addListener(client, getStatus.directory(), getStatus.stamp());

        client.sendOrLog(updates);

        return true;
    }
}