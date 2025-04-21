package org.gamboni.mserver.tech.ui;

import lombok.RequiredArgsConstructor;
import org.gamboni.tech.history.ClientStateHandler;
import org.gamboni.tech.history.ui.DynamicPage;
import org.gamboni.tech.history.ui.DynamicPageMember;
import org.gamboni.tech.web.js.JavaScript;
import org.gamboni.tech.web.js.JsType;
import org.gamboni.tech.web.ui.Css;
import org.gamboni.tech.web.ui.Element;
import org.gamboni.tech.web.ui.IdentifiedElementRenderer;
import org.gamboni.tech.web.ui.RepeatingTask;
import org.gamboni.tech.web.ui.value.Value;

import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.BiFunction;
import java.util.function.Function;
import java.util.function.Predicate;

import static java.util.stream.Collectors.joining;
import static lombok.AccessLevel.PRIVATE;
import static org.gamboni.tech.web.js.JavaScript.JsHtmlElement;
import static org.gamboni.tech.web.js.JavaScript.JsStatement;
import static org.gamboni.tech.web.js.JavaScript.getElementById;
import static org.gamboni.tech.web.js.JavaScript.let;
import static org.gamboni.tech.web.js.JavaScript.literal;
import static org.gamboni.tech.web.js.JavaScript.seq;
import static org.gamboni.tech.web.js.JavaScript.toSeq;
import static org.gamboni.tech.web.ui.Html.attribute;
import static org.gamboni.tech.web.ui.RepeatingTask.StartContext.ON_LOAD;

/** Similar to EnumViewElementTemplate but showing a numerical value. */
@RequiredArgsConstructor(access = PRIVATE)
public final class NumberViewElementTemplate<T extends JsType<?>> implements DynamicPageMember<Object, IdentifiedElementRenderer<T>> {

    private static final Function<Object, List<Css.PropertyValue>> NO_STYLE = __ -> List.of();

    private final String elementKey = "";
    private final Element base;
    private final BiFunction<JavaScript.JsExpression, ClientStateHandler.MatchCallback, T> matcher;
    private final Function<? super T, List<Css.PropertyValue>> style;

    @RequiredArgsConstructor(access = PRIVATE)
    public static class Builder {
        private final Element base;

        public <U extends JsType<?>> NumberViewElementTemplate<U> withEventMatcher(BiFunction<JavaScript.JsExpression, ClientStateHandler.MatchCallback, U> matcher) {
            return new NumberViewElementTemplate<>(base, matcher, NO_STYLE);
        }
    }

    public static Builder ofStaticBase(Element base) {
        return new Builder(base);
    }

    public NumberViewElementTemplate<T> withStyle(BiFunction<Css.PropertyValues, T, Css.PropertyValue> function) {
        return new NumberViewElementTemplate<>(base, matcher, t -> List.of(function.apply(Css.PropertyValues.INSTANCE, t)));
    }

    @Override
    public <P extends DynamicPage<?>> IdentifiedElementRenderer<T> addTo(P page) {

        String idPrefix = page.freshElementId("numeric");
        Map<Class<?>, RepeatingTask> updaters = new HashMap<>();
        page.addHandler(matcher, event -> {
            var applier = new PropertyApplier(idPrefix, style.apply(event));
            var todoInHandler = new ArrayList<JsStatement>();
            // apply time-dependent properties (if any) from timer
            applier.updateElement(prop1 -> prop1.value().isTimeDependent())
                    .ifPresent(updateLogic -> {
                        var rt = RepeatingTask.create(
                                        updateLogic,
                                        Duration.ofSeconds(1))
                                .addTo(page);

                        // SHOULD WE not pre-initialise style on timer auto-load because it would get cached?
                        // HOWEVER, we risk the "flash of unstyled content" if we don't

                        updaters.put(event.getBackendType(), rt);

                        todoInHandler.add(rt.start(RepeatingTask.StartContext.GENERAL));
                    });

            // apply non-time-dependent properties from event handler
            applier.updateElement(prop -> !prop.value().isTimeDependent())
                    .ifPresent(todoInHandler::add);

            // stop repeating tasks of other matchers, if applicable
            todoInHandler.add(JavaScript.dynamicStatement(() ->
                updaters.entrySet().stream()
                        .filter(e -> e.getKey() != event.getBackendType())
                        .map(e -> e.getValue().stop())
                        .collect(toSeq())));

            return seq(todoInHandler);
        });

        /* Create a renderer */
        return IdentifiedElementRenderer.of(elementKey, value -> {
            Value<String> eltId = Value.of(idPrefix);
            List<Css.PropertyValue> styleValues = style.apply(value);
            Element rendered = base.withAttribute(attribute("id", eltId));
            if (!styleValues.isEmpty()) {
                rendered = rendered.withAttribute(attribute("style",
                        styleValues.stream()
                                .map(Css.PropertyValue::render)
                                .collect(joining(" "))));
            }
            if (updaters.containsKey(value.getBackendType())) {
                return rendered.withOnLoad(
                        ClientStateHandler.EVENT_SYMBOL.assignIn(value,
                                updaters.get(value.getBackendType())
                                .start(ON_LOAD)));
            } else {
                return rendered;
            }
        });
    }

    private record PropertyApplier(String idPrefix, List<Css.PropertyValue> properties) {
        private Optional<JsStatement> updateElement(Predicate<Css.PropertyValue> predicate) {
            if (properties.stream().anyMatch(predicate)) {
                return Optional.of(let(
                        getElementById(literal(idPrefix)), // NOTE: we don't support multiple renders (with ids) for now
                        JsHtmlElement::new,
                        elt ->
                                properties.stream()
                                        .filter(predicate)
                                        .map(prop -> prop.apply(elt))
                                        .collect(toSeq())));
                // TODO also clear styles not set in the current event
            } else {
                return Optional.empty();
            }
        }
    }
}
