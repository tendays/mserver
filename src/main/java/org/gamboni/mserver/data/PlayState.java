package org.gamboni.mserver.data;

import com.google.common.collect.LinkedHashMultimap;
import com.google.common.collect.Multimap;
import org.gamboni.mserver.ui.Style;
import org.gamboni.tech.web.js.JavaScript;
import org.gamboni.tech.web.ui.Css;

import java.util.Collection;
import java.util.Optional;
import java.util.function.Function;

import static org.gamboni.tech.web.js.JavaScript.EMPTY_IF_CHAIN;
import static org.gamboni.tech.web.js.JavaScript.toSeq;

public enum PlayState {
    LOADING,
    STOPPED,
    PLAYING(style -> style.nowPlaying),
    QUEUED(style -> style.queued),
    PAUSED(style -> style.nowPlaying);

    private final Optional<Function<Style, Css.ClassName>> cssClass;

    PlayState() {
        cssClass = Optional.empty();
    }

    PlayState(Function<Style, Css.ClassName> cssClass) {
        this.cssClass = Optional.of(cssClass);
    }

    public Css.ClassList applyStyle(Style style, Css.ClassList baseStyle) {
        return cssClass.map(css -> baseStyle.and(css.apply(style)))
                .orElse(baseStyle);
    }

    /** Generate code updating the given class list to reflect the state in the jsPlayState variable. */
    public static JavaScript.JsStatement jsApplyStyle(Style style, JavaScript.JsDOMTokenList classList, JavaScript.JsExpression jsPlayState) {
        // NOTE: this code is deliberately written in a fully generic way to allow future move into a tech package or module
        Multimap<Css.ClassName, PlayState> cases = LinkedHashMultimap.create();

        for (var ps : PlayState.values()) {
            ps.cssClass.ifPresent(
                    fun -> cases.put(fun.apply(style), ps)
            );
        }

        return cases.asMap()
                .entrySet()
                .stream()
                .reduce(EMPTY_IF_CHAIN,
                        (chain, entry) -> chain._elseIf(isOneOf(jsPlayState, entry.getValue()),
                                        /* Remove other classes */
                                        cases.keySet()
                                                .stream()
                                                .filter(thatClass -> !thatClass.equals(entry.getKey()))
                                                        .map(classList::remove)
                                                .collect(toSeq()),

                                classList.add(entry.getKey())),
                                (x, y) -> {throw new UnsupportedOperationException();})

                ._else(cases.keySet()
                        .stream()
                        .map(classList::remove));
    }

    private static JavaScript.JsExpression isOneOf(JavaScript.JsExpression value, Collection<? extends Enum<?>> collection) {
        return collection.stream()
                .map(value::eq)
                .reduce(JavaScript.JsExpression::or)
                .orElseThrow();
    }

    public PlayState togglePaused() {
        return switch (this) {
            case PAUSED -> PLAYING;
            case PLAYING -> PAUSED;
            default -> this;
        };
    }
}
