package org.gamboni.mserver.ui;

import org.gamboni.mserver.DirectorySnapshot;
import org.gamboni.mserver.MServerController;
import org.gamboni.mserver.MServerSocket;
import org.gamboni.mserver.data.*;
import org.gamboni.mserver.tech.Mapping;
import org.gamboni.tech.history.ClientStateHandler;
import org.gamboni.tech.sparkjava.SparkPage;
import org.gamboni.tech.web.js.JavaScript;
import org.gamboni.tech.web.js.JsPersistentWebSocket;
import org.gamboni.tech.web.ui.Css;
import org.gamboni.tech.web.ui.FavIconResource;
import org.gamboni.tech.web.ui.Html;
import org.gamboni.tech.web.ui.IdentifiedElement;

import java.io.File;
import java.util.List;

import static org.gamboni.tech.web.js.JavaScript.*;
import static org.gamboni.tech.web.ui.Html.*;

public class DirectoryPage extends SparkPage<DirectoryPage.Data> {

    // TODO see if we can put all the information in DirectorySnapshot?
    public record Data(DirectorySnapshot snapshot, File folder, Iterable<Item> files) {}

    private final MServerController controller;
    private final Mapping mapping;
    private final Style style;

    public final IdentifiedElement progress;

    private final JavaScript.JsGlobal playState = new JavaScript.JsGlobal("playState");
    private final JavaScript.JsGlobal directory = new JavaScript.JsGlobal("directory");

    public final JavaScript.Fun showStatus = new JavaScript.Fun("showStatus");

    private final ClientStateHandler stateHandler = new ClientStateHandler() {
        @Override
        protected JsExpression helloValue(JsExpression stamp) {
            return JsGetStatus.literal(directory, stamp);
        }
    };

    private final JsPersistentWebSocket socket;

    private JsStatement setProgressBarPercent(JsExpression value) {
        return progress.find().style().dot("width")
                .set(value.plus("%"));
    }

    public DirectoryPage(MServerController controller, Mapping mapping, Style style, MServerSocket serverSocket) {
        this.controller = controller;
        this.mapping = mapping;
        this.style = style;
        this.progress = setId("progress").to(div(List.of(style.progress)));

        // event can be either a JsFileState or a JsGlobalState.

        // NOTE: should call setStatus, but then should first cancel the existing
        // setTimeout (or, alternatively, call a setStatus which doesn't do a setTimeout)
        stateHandler.addHandler(
                // if there's a 'file' then it's really a JsFileState.
                (event, matcher) -> {
                    var fileState = new JsFileState(event);
                    matcher.expect(fileState.file());
                    return fileState;
                }, event -> let(getElementById(event.file()).classList(),
                        JsDOMTokenList::new,
                        classList -> PlayState.jsApplyStyle(
                                style,
                                classList,
                                event.state())));

        // else, it's a JsGlobalState
        stateHandler.addHandler((event, matcher) -> new JsGlobalState(event),
                event -> playState.set(JsFrontEndState.literal(
                        event.state(),
                        event.position(),
                        getTime().divide(1000).minus(event.position()),
                        event.duration()
                )));

        this.socket = serverSocket.createClient(stateHandler);

        // to easily access properties
        var typedStatus = new JsFrontEndState(this.playState);
        controller.addTo(this);
        addToScript(
                directory.declare(literal("")),
                playState.declare(JsFrontEndState.literal(
                        literal(PlayState.LOADING),
                        literal(0),
                        literal(0),
                        literal(0))),
                showStatus.declare(seq(
                        status.find().setInnerHtml(typedStatus.state()),
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
                )));

        socket.addTo(this);

        addToOnLoad(onLoad ->
                directory.set(
                        onLoad.addParameter(data ->
                                literal(mapping.fileToPath(data.folder)))));
        addToOnLoad(onLoad ->
                stateHandler.init(
                        onLoad.addParameter(data ->
                                literal(data.snapshot.stamp()))));

        addToOnLoad(onLoad -> socket.poll());
        addToOnLoad(onLoad -> showStatus.invoke());
    }

    public final IdentifiedElement status = setId("status").to(p(escape("Loading…")));

    public Html render(Data data) {
        return html(data, List.of(style, new FavIconResource("favicon.png", "image/png")), List.of(
                        div(List.of(style.top),
                                p(
                                        button("Play/Pause", controller.pause),
                                        escape(" "),
                                        button("Skip", controller.skip),
                                        escape(" "),
                                        button("Stop", controller.stop)),
                                status,
                                div(List.of(style.progressBar),
                                        progress)),
                        ul(style.grid, data.files, style.item, item -> box(data.snapshot.getFileState(item.file), item))
                )
        );
    }

    private Html box(PlayState state, Item item) {
        Css.ClassList bodyStyle = state.applyStyle(style, style.itemBody);
        String id = mapping.fileToPath(item.file);
        if (item.isDirectory()) {
            return a(List.of(
                    attribute("id", id),
                    bodyStyle), "./"+ item.name +"/",
                    span(style.label,
                    escape(item.friendlyName())), thumb(item));
        } else if (item.isMusic()) {
            return div(List.of(
                            attribute("id", id),
                            bodyStyle,
                            attribute("onclick",
                                    controller.jsPlay(item.file))),
                    span(style.label,
                            escape(item.friendlyName())),
                    thumb(item));
        } else {
            return EMPTY;
        }
    }

    private Html thumb(Item item) {
        File thumbPath = new File(item.file +".jpeg");
        if (thumbPath.exists()) {
            return img(style.thumb, ("./"+ item.name +".jpeg"));
        } else {
            return Html.EMPTY;
        }

    }
}
