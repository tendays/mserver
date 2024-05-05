package org.gamboni.mserver.ui;

import lombok.Setter;
import org.gamboni.mserver.MServerController;
import org.gamboni.mserver.MServerSocket;
import org.gamboni.mserver.data.JsFrontEndState;
import org.gamboni.mserver.data.JsStatus;
import org.gamboni.mserver.data.PlayState;
import org.gamboni.tech.sparkjava.SparkScript;
import org.gamboni.tech.web.js.JsPersistentWebSocket;

import static org.gamboni.tech.web.js.JavaScript.*;

public class Script extends SparkScript {
    private final MServerController controller;
    private final MServerSocket serverSocket;

    private final JsGlobal playState = new JsGlobal("playState");

    // can't be set at construction time because of circularity: the page also needs to inject the script...
    @Setter
    private DirectoryPage page;

    public final Fun showStatus = new Fun("showStatus");

    private final JsPersistentWebSocket socket;

    public Script(MServerController controller, MServerSocket serverSocket) {
        this.controller = controller;
        this.serverSocket = serverSocket;
        this.socket = serverSocket.createClient(
                literal(MServerSocket.GET_STATUS),
             message -> {
                var payload = new JsStatus(message);
                // NOTE: should call setStatus, but then should first cancel the existing
                // setTimeout (or, alternatively, call a setStatus which doesn't do a setTimeout)
                return playState.set(JsFrontEndState.literal(
                        payload.state(),
                        payload.position(),
                        getTime().divide(1000).minus(payload.position()),
                        payload.duration()
                ));
            });
    }

    public JsStatement doOnLoad() {
        return seq(socket.poll(),
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
                                _if(typedStatus.state().eq(PlayState.PLAYING),
                                        setProgressBarPercent(getTime().divide(1000).minus(typedStatus.playStarted())
                                                .times(100)
                                                .divide(typedStatus.duration())))
                                        ._elseIf(typedStatus.state().eq(PlayState.PAUSED),
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
