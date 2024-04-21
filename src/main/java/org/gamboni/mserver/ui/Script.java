package org.gamboni.mserver.ui;

import lombok.RequiredArgsConstructor;
import lombok.Setter;
import org.gamboni.mserver.MServerController;
import org.gamboni.mserver.MServerSocket;
import org.gamboni.mserver.data.JsStatusDTO;
import org.gamboni.mserver.tech.AbstractController;
import org.gamboni.tech.web.js.JavaScript;
import org.gamboni.tech.web.js.JavaScript.Fun;
import org.gamboni.tech.web.js.JsPersistentWebSocket;
import org.gamboni.tech.web.ui.AbstractPage;
import org.gamboni.tech.web.ui.AbstractScript;
import spark.Spark;

import static org.gamboni.tech.web.js.JavaScript.*;

public class Script extends AbstractScript {
    private final MServerController controller;

    private final JsPersistentWebSocket socket = new JsPersistentWebSocket(MServerSocket.PATH) {
        @Override
        protected JsExpression helloString() {
            return literal("connect");
        }

        @Override
        protected JsStatement handleEvent(JsExpression message) {
            return JsStatement.of(consoleLog(message));
        }
    };

    public final Fun showStatus = new Fun("showStatus");

    // can't be set at construction time because of circularity: the page also needs to inject the script...
    @Setter
    private DirectoryPage page;

    public Script(MServerController controller) {
        this.controller = controller;

        Spark.get(getUrl(), (req, res) -> {
            res.header("Content-Type", getMime());
            return this.render();
        });
    }

    @Override
    public String render() {
        return controller.getJavascript() +
                showStatus.declare(() -> controller.getStatus.call(result ->
                                let(JavaScript.jsonParse(result),
                                        JsStatusDTO::new,
                                        j -> seq(
                                                page.status.find().setInnerHtml(j.text()),
                                                page.progress.find().style().dot("width").set(j.progress()),
                                                setTimeout(showStatus.invoke(), 1000)
                                        )
                                )
                        )
                );
    }
}
