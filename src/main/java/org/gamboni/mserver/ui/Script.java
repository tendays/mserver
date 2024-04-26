package org.gamboni.mserver.ui;

import lombok.RequiredArgsConstructor;
import lombok.Setter;
import org.gamboni.mserver.MServerController;
import org.gamboni.mserver.MServerSocket;
import org.gamboni.mserver.data.JsFrontEndState;
import org.gamboni.mserver.data.JsStatus;
import org.gamboni.mserver.data.PlayState;
import org.gamboni.mserver.tech.SparkScript;
import org.gamboni.tech.web.js.JsPersistentWebSocket;

import static org.gamboni.tech.web.js.JavaScript.*;

@RequiredArgsConstructor
public class Script extends SparkScript {
    private final MServerController controller;

    private final JsGlobal status = new JsGlobal("status");

    // can't be set at construction time because of circularity: the page also needs to inject the script...
    @Setter
    private DirectoryPage page;

    public final Fun showStatus = new Fun("showStatus");

    private final JsPersistentWebSocket socket = new JsPersistentWebSocket(MServerSocket.PATH) {
        @Override
        protected JsExpression helloString() {
            return literal("getStatus");
        }

        @Override
        protected JsStatement handleEvent(JsExpression message) {
            var payload = new JsStatus(message);
            // NOTE: should call setStatus, but then should first cancel the existing
            // setTimeout (or, alternatively, call a setStatus which doesn't do a setTimeout)
            return status.set(JsFrontEndState.literal(
                    payload.state(),
                    getTime().minus(payload.position()),
                    payload.duration()
            ));
        }
    };

    @Override
    public String render() {
        // to easily access properties
        var typedStatus = new JsFrontEndState(this.status);

        return controller.getJavascript() +
                this.status.declare(JsFrontEndState.literal(
                        literal(PlayState.UNKNOWN),
                        literal(0),
                        literal(0))) +
                showStatus.declare(() ->
                        seq(
                                page.status.find().setInnerHtml(typedStatus.state()),
                                page.progress.find().style().dot("width")
                                        .set(
                                                getTime().minus(typedStatus.started())
                                                        .times(100)
                                                        .divide(typedStatus.duration())
                                                        .plus("%")),
                                setTimeout(showStatus.invoke(), 1000)
                        ));
    }
}
