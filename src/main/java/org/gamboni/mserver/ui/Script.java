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

    private final JsGlobal playState = new JsGlobal("playState");

    // can't be set at construction time because of circularity: the page also needs to inject the script...
    @Setter
    private DirectoryPage page;

    public final Fun showStatus = new Fun("showStatus");

    private final JsPersistentWebSocket socket = new JsPersistentWebSocket(MServerSocket.PATH) {
        @Override
        protected JsExpression helloString() {
            return literal(MServerSocket.GET_STATUS);
        }

        @Override
        protected JsStatement handleEvent(JsExpression message) {
            var payload = new JsStatus(message);
            // NOTE: should call setStatus, but then should first cancel the existing
            // setTimeout (or, alternatively, call a setStatus which doesn't do a setTimeout)
            return playState.set(JsFrontEndState.literal(
                    payload.state(),
                    payload.position(),
                    getTime().divide(1000).minus(payload.position()),
                    payload.duration()
            ));
        }
    };

    public JsStatement doOnLoad() {
        return
                seq(socket.poll(),
                        showStatus.invoke());
    }

    @Override
    public String render() {
        // to easily access properties
        var typedStatus = new JsFrontEndState(this.playState);
        return controller.getJavascript() +
                socket.declare() +
                this.playState.declare(JsFrontEndState.literal(
                        literal(PlayState.LOADING),
                        literal(0),
                        literal(0),
                        literal(0))) +
                showStatus.declare(() ->
                        seq(
                                page.status.find().setInnerHtml(typedStatus.state()),
                                _if(typedStatus.state().eq(literal(PlayState.PLAYING)),
                                        setProgressBarPercent(getTime().divide(1000).minus(typedStatus.playStarted())
                                                .times(100)
                                                .divide(typedStatus.duration())))
                                        ._elseIf(typedStatus.state().eq(literal(PlayState.PAUSED)),
                                                setProgressBarPercent(typedStatus.pausedPosition()
                                                        .times(100)
                                                        .divide(typedStatus.duration()))
                                                )
                                        ._else(setProgressBarPercent(literal(0))),
                                setTimeout(showStatus.invoke(), 1000)
                        ));
    }

    private JsStatement setProgressBarPercent(JsExpression value) {
        return page.progress.find().style().dot("width")
                .set(value.plus("%"));
    }
}
