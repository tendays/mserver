package org.gamboni.mserver;

import com.google.common.collect.Multimap;
import lombok.Setter;
import lombok.extern.slf4j.Slf4j;
import org.eclipse.jetty.websocket.api.annotations.WebSocket;
import org.gamboni.mserver.data.*;
import org.gamboni.mserver.tech.Mapping;
import org.gamboni.tech.sparkjava.SparkWebSocket;

import java.util.List;

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

    public void broadcastState(Status newState, Multimap<Client, FileState> notifications) {
        broadcast(client -> StatusUpdate.forStatus(
                newState,
                List.copyOf(notifications.get(client))
        ));
    }

    @Override
    protected boolean handleMessage(Client client, String message) {
        var getStatus = mapping.readValue(message, GetStatus.class);
        DirectoryState directoryState = controller.directoryState(getStatus.directory());
        directoryState.addListener(client);

        client.sendOrLog(StatusUpdate.forStatus(
                controller.getCurrentStatus(),
                directoryState.getUpdatesSince(getStatus.stamp())));

        return true;
    }
}