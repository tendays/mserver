package org.gamboni.mserver.ui;

import com.google.common.collect.Iterables;
import org.gamboni.mserver.DirectorySnapshot;
import org.gamboni.mserver.DirectorySnapshot.ItemSnapshot;
import org.gamboni.mserver.MServerController;
import org.gamboni.mserver.data.JsFrontEndState;
import org.gamboni.mserver.data.JsGetStatus;
import org.gamboni.mserver.data.JsGlobalState;
import org.gamboni.mserver.data.PlayState;
import org.gamboni.mserver.tech.Mapping;
import org.gamboni.mserver.tech.SparkDynamicPage;
import org.gamboni.mserver.tech.ui.TimeBasedTemplate;
import org.gamboni.tech.history.Stamped;
import org.gamboni.tech.history.ui.EnumViewElementTemplate;
import org.gamboni.tech.web.js.JavaScript;
import org.gamboni.tech.web.ui.FavIconResource;
import org.gamboni.tech.web.ui.Html;
import org.gamboni.tech.web.ui.IdentifiedElementRenderer;
import org.gamboni.tech.web.ui.Value;

import java.io.File;
import java.util.List;

import static org.gamboni.mserver.data.PlayState.PAUSED;
import static org.gamboni.mserver.data.PlayState.STOPPED;
import static org.gamboni.tech.web.js.JavaScript.*;
import static org.gamboni.tech.web.ui.Html.attribute;
import static org.gamboni.tech.web.ui.Html.escape;

public class DirectoryPage extends SparkDynamicPage<DirectoryPage.Data> {

    private final IdentifiedElementRenderer<ItemSnapshot> itemTemplate;

    // TODO see if we can put all the information in DirectorySnapshot?
    public record Data(DirectorySnapshot snapshot, File folder, Iterable<ItemSnapshot> files, PlayState globalState) implements Stamped {
        @Override
        public long stamp() {
            return snapshot.stamp();
        }
    }

    private final MServerController controller;
    private final Style style;

    public final IdentifiedElementRenderer<PlayState> status;
    private final TimeBasedTemplate progress;

    private final JavaScript.JsGlobal playState = new JavaScript.JsGlobal("playState");
    private final JavaScript.JsGlobal directory = new JavaScript.JsGlobal("directory");

    private JsStatement setProgressBarPercent(JsHtmlElement bar, JsExpression value) {
        return bar.style().dot("width")
                .set(value.plus("%"));
    }

    public DirectoryPage(MServerController controller, Mapping mapping, Style style) {
        this.controller = controller;
        this.style = style;
        this.status = EnumViewElementTemplate
                .<PlayState, PlayState>ofStaticBase(PlayState.class, __ -> Value.of("global"), Value::of,
                        p())
                .withEventMatcher((event, callback) -> callback.expectSameType(new JsGlobalState(event)),
                        __ -> literal("global"),
                        JsGlobalState::state)
                .withContents(Enum::name)
                .addTo(this);

        this.itemTemplate = EnumViewElementTemplate.<ItemSnapshot, PlayState>ofDynamicBase(PlayState.class,
                        i -> Value.of(mapping.fileToPath(i.item().file)),
                        i -> Value.of(i.state()),
                        item -> {
                            if (item.isDirectory()) {
                                return a(
                                        style.itemBody,
                                        "./" + item.name() + "/",
                                        span(style.label,
                                                escape(item.friendlyName())),
                                        thumb(item));
                            } else { //if (item.item.isMusic()) {
                                return div(List.of(attribute("onclick", controller.jsPlay(item.file())),
                                                style.itemBody),
                                        span(style.label, escape(item.friendlyName())),
                                        thumb(item));
                            }
                        })
                .withStyle(Style.states)
                .addTo(this);

        stateHandler.addHandler((event, matcher) -> matcher.expectSameType(new JsGlobalState(event)),
                event -> playState.set(JsFrontEndState.literal(
                        event.state(),
                        event.position(),
                        getTime().divide(1000).minus(event.position()),
                        event.duration()
                )));

        // ALSO: put the 'key' into Event and get rid of 'matcher'?
        // (OR less violent, introduce interface KeyedEvent and simpler addHandler API overload)

        // to easily access properties
        var typedStatus = new JsFrontEndState(this.playState);

        this.progress = TimeBasedTemplate.create(div(List.of(style.progress)),
                div -> _if(typedStatus.state().eq(literal(STOPPED)),
                        setProgressBarPercent(div, literal(0)))
                        ._elseIf(typedStatus.state().eq(literal(PAUSED)),
                                setProgressBarPercent(div, typedStatus.pausedPosition()
                                        .times(100)
                                        .divide(typedStatus.duration()))
                        )
                        ._else( // playing
                                setProgressBarPercent(div, getTime().divide(1000).minus(typedStatus.playStarted())
                                        .times(100)
                                        .divide(typedStatus.duration())))
        ).addTo(this);

        controller.addTo(this);
        addToScript(
                directory.declare(literal("")),
                playState.declare(JsFrontEndState.literal(
                        literal(STOPPED),
                        literal(0),
                        literal(0),
                        literal(0))));

        addToOnLoad(onLoad ->
                directory.set(
                        onLoad.addParameter(data ->
                                literal(mapping.fileToPath(data.folder)))));
    }

    @Override
    protected JsExpression helloValue(JsExpression stamp) {
        return JsGetStatus.literal(directory, stamp);
    }

    public Html render(Data data) {
        return html(data, List.of(style, new FavIconResource("favicon.png", "image/png")), List.of(
                        div(List.of(style.top),
                                p(
                                        button("Play/Pause", controller.pause),
                                        escape(" "),
                                        button("Skip", controller.skip),
                                        escape(" "),
                                        button("Stop", controller.stop)),
                                status.render(data.globalState()),
                                div(List.of(style.progressBar),
                                        progress.render())),
                        ul(style.grid,
                                Iterables.filter(
                                data.files,
                                        f -> f.item().isDirectory() || f.item().isMusic()),
                                style.item,
                                itemTemplate::render)
                )
        );
    }

    private Html thumb(ItemSnapshot item) {
        File thumbPath = new File(item.file() +".jpeg");
        if (thumbPath.exists()) {
            return img(style.thumb, ("./"+ item.name() +".jpeg"));
        } else {
            return Html.EMPTY;
        }

    }
}
