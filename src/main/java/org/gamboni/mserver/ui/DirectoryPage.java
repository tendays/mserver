package org.gamboni.mserver.ui;

import com.google.common.collect.Iterables;
import org.gamboni.mserver.DirectorySnapshot;
import org.gamboni.mserver.DirectorySnapshot.ItemSnapshot;
import org.gamboni.mserver.MServerController;
import org.gamboni.mserver.data.*;
import org.gamboni.mserver.tech.Mapping;
import org.gamboni.mserver.tech.SparkDynamicPage;
import org.gamboni.mserver.tech.ui.NumberViewElementTemplate;
import org.gamboni.tech.history.Stamped;
import org.gamboni.tech.history.ui.EnumViewElementTemplate;
import org.gamboni.tech.web.js.JavaScript;
import org.gamboni.tech.web.js.JsType;
import org.gamboni.tech.web.ui.FavIconResource;
import org.gamboni.tech.web.ui.Html;
import org.gamboni.tech.web.ui.IdentifiedElementRenderer;
import org.gamboni.tech.web.ui.value.DateValue;
import org.gamboni.tech.web.ui.value.Value;

import java.io.File;
import java.util.List;
import java.util.function.Function;

import static org.gamboni.mserver.data.PlayState.PAUSED;
import static org.gamboni.mserver.data.PlayState.PLAYING;
import static org.gamboni.mserver.data.PlayState.STOPPED;
import static org.gamboni.tech.web.js.JavaScript.JsExpression;
import static org.gamboni.tech.web.js.JavaScript.JsHtmlElement;
import static org.gamboni.tech.web.js.JavaScript.JsStatement;
import static org.gamboni.tech.web.js.JavaScript.literal;
import static org.gamboni.tech.web.ui.Html.attribute;
import static org.gamboni.tech.web.ui.Html.escape;

public class DirectoryPage extends SparkDynamicPage<DirectoryPage.Data> {

    private final IdentifiedElementRenderer<ItemSnapshot> itemTemplate;

    // TODO see if we can put all the information in DirectorySnapshot?
    public record Data(DirectorySnapshot snapshot, File folder, Iterable<ItemSnapshot> files, GlobalState globalState) implements Stamped {
        @Override
        public long stamp() {
            return snapshot.stamp();
        }
    }

    private final MServerController controller;
    private final Style style;

    public final IdentifiedElementRenderer<PlayState> status;
    private final /*TimeBasedTemplate*/ IdentifiedElementRenderer<JsType<? extends GlobalState>> progress;

    private final JavaScript.JsGlobal playState = new JavaScript.JsGlobal("playState");
    private final JavaScript.JsGlobal directory = new JavaScript.JsGlobal("directory");

    private JsStatement setProgressBarPercent(JsHtmlElement bar, JsExpression value) {
        return bar.style().dot("width")
                .set(value.plus("%"));
    }

    public static final Function<Class<? extends GlobalState>, PlayState> PLAY_STATE_FUNCTION =
            eventType -> {
        if (eventType == PausedGlobalState.class) {
            return PAUSED;
        } else if (eventType == PlayingGlobalState.class) {
            return PLAYING;
        } else if (eventType == StoppedGlobalState.class) {
            return STOPPED;
        } else {
            throw new IllegalStateException(eventType.getSimpleName());
        }
    };

    public DirectoryPage(MServerController controller, Mapping mapping, Style style) {
        this.controller = controller;
        this.style = style;
        this.status = EnumViewElementTemplate
                .<PlayState, PlayState>ofStaticBase(PlayState.class, __ -> Value.of("global"), Value::of,
                    p())
                .withEventMatcher((event, callback) ->
                    new EnumViewElementTemplate.EventData(literal("global"),
                            Value.of(PLAY_STATE_FUNCTION.apply(
                            callback.expectOneOf(
                                    PausedGlobalStateValues.of(event),
                                    PlayingGlobalStateValues.of(event),
                                    StoppedGlobalStateValues.of(event))
                                    .getBackendType()))))
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

        // ALSO: put the 'key' into Event and get rid of 'matcher'?
        // (OR less violent, introduce interface KeyedEvent and simpler addHandler API overload)

        this.progress = NumberViewElementTemplate.ofStaticBase(div(List.of(style.progress)))
                .<JsType<? extends GlobalState>>withEventMatcher((event, cb) ->
                        cb.expectOneOf(
                                StoppedGlobalStateValues.of(event),
                                PlayingGlobalStateValues.of(event),
                                PausedGlobalStateValues.of(event)))
                .withStyle((css, value) -> {
                    if (value instanceof PausedGlobalStateValues paused) {
                        return css.width(
                                paused.position().divide(paused.duration()));
                    } else if (value instanceof PlayingGlobalStateValues playing) {
                        return css.width(
                                DateValue.now().minus(playing.started()).divide(playing.duration())
                        );
                    } else /* if (value instanceof StoppedGlobalStateValues) */ {
                        return css.width("0");
                    }
                }
        ).addTo(this);

        controller.addTo(this);
        addToScript(
                directory.declare(literal(""))/*,
                playState.declare(FrontEndStateValues.literal(
                        literal(STOPPED),
                        literal(0),
                        literal(0),
                        literal(0)))*/);

        addToOnLoad(onLoad ->
                directory.set(
                        onLoad.addParameter(data ->
                                literal(mapping.fileToPath(data.folder)))));
    }

    @Override
    protected JsExpression helloValue(JsExpression stamp) {
        return GetStatusValues.literal(directory, stamp);
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
                                status.render(PLAY_STATE_FUNCTION.apply(
                                        data.globalState().getClass())),
                                div(List.of(style.progressBar),
                                        progress.render(GlobalStateValues.of(data.globalState())    ))),
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
