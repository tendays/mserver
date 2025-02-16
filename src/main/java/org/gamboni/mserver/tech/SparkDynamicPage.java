package org.gamboni.mserver.tech;

import org.gamboni.tech.history.ClientStateHandler;
import org.gamboni.tech.history.Stamped;
import org.gamboni.tech.history.ui.DynamicPage;
import org.gamboni.tech.sparkjava.SparkPage;
import org.gamboni.tech.web.js.JavaScript;
import org.gamboni.tech.web.js.JsPersistentWebSocket;

import java.util.function.BiFunction;
import java.util.function.Function;

import static org.gamboni.tech.web.js.JavaScript.literal;

public abstract class SparkDynamicPage<T extends Stamped> extends SparkPage<T> implements DynamicPage<T> {

    protected final ClientStateHandler stateHandler = new ClientStateHandler() {
        @Override
        protected JavaScript.JsExpression helloValue(JavaScript.JsExpression stamp) {
            // hack alert: we don't evaluate outer helloValue() directly, but only when format() is called.
            // This method s called from the SparkDynamicPage constructor when the socket is initialised,
            // and this hack allows the SparkDynamicPage to initialise.
            return s -> SparkDynamicPage.this.helloValue(stamp).format(s);
        }
    };

    private final JsPersistentWebSocket socket = new JsPersistentWebSocket(stateHandler);

    protected SparkDynamicPage() {
        socket.addTo(this);
        addToOnLoad(onLoad -> stateHandler.init(onLoad.addParameter(
                data -> literal(data.stamp()))));
    }

    protected abstract JavaScript.JsExpression helloValue(JavaScript.JsExpression stamp);

    public <E> DynamicPage<T> addHandler(BiFunction<JavaScript.JsExpression, ClientStateHandler.MatchCallback, E> matcher,
                                         Function<E, JavaScript.JsFragment> handler) {
        stateHandler.addHandler(matcher, handler);
        return this;
    }

    public JavaScript.JsExpression submitMessage(JavaScript.JsExpression payload) {
        return socket.submit(payload);
    }
}
