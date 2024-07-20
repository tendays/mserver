package org.gamboni.mserver.ui;

import lombok.Setter;
import org.gamboni.mserver.MServerController;
import org.gamboni.mserver.MServerSocket;
import org.gamboni.mserver.data.*;
import org.gamboni.tech.history.JsStampedEventList;
import org.gamboni.tech.sparkjava.SparkScript;
import org.gamboni.tech.web.js.JsPersistentWebSocket;

import static org.gamboni.tech.web.js.JavaScript.*;

public class Script extends SparkScript {
    private final MServerController controller;

    private final JsGlobal playState = new JsGlobal("playState");
    private final JsGlobal directory = new JsGlobal("directory");
    private final JsGlobal stamp = new JsGlobal("stamp");

    // can't be set at construction time because of circularity: the page also needs to inject the script...
    @Setter
    private DirectoryPage page;

    public final Fun showStatus = new Fun("showStatus");

    private final JsPersistentWebSocket socket;

    public Script(Style style, MServerController controller, MServerSocket serverSocket) {
        this.controller = controller;

        this.socket = serverSocket.createClient(
                JsGetStatus.literal(directory, stamp),
             message -> {
                var payload = new JsStampedEventList(message);

                // NOTE: should call setStatus, but then should first cancel the existing
                // setTimeout (or, alternatively, call a setStatus which doesn't do a setTimeout)
                return seq(
                        stamp.set(payload.stamp()),
                        _forOf(payload.updates(), arrayElt -> {
                            // arrayElt can be either a JsFileState or a JsGlobalState.
                            var fileState = new JsFileState(arrayElt);
                            var newGlobalState = new JsGlobalState(arrayElt);
                            // if there's a 'file' then it's really a JsFileState.
                            return _if(fileState.file(), let(getElementById(fileState.file()).classList(),
                                    JsDOMTokenList::new,
                                    classList -> PlayState.jsApplyStyle(
                                            style,
                                            classList,
                                            fileState.state())))
                                    ._else(
                                            // else, it's a JsGlobalState
                                            playState.set(JsFrontEndState.literal(
                                                    newGlobalState.state(),
                                                    newGlobalState.position(),
                                                    getTime().divide(1000).minus(newGlobalState.position()),
                                                    newGlobalState.duration()
                                            )
                                    ));
                        }));
            });
    }

    public JsStatement doOnLoad(String actualDirectory, long initialStamp) {
        return seq(
                directory.set(literal(actualDirectory)),
                stamp.set(literal(initialStamp)),
                socket.poll(),
                        showStatus.invoke());
    }

    @Override
    public String render() {
        // to easily access properties
        var typedStatus = new JsFrontEndState(this.playState);
        return controller.getJavascript() +
                directory.declare(literal("")) +
                stamp.declare(0) +
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
