package org.gamboni.mserver.data;

import org.gamboni.tech.history.event.Event;

public interface GlobalState extends Event {
    GlobalState STOPPED = new StoppedGlobalState();
}
