            package org.gamboni.mserver.tech.ui;

            import org.gamboni.tech.web.js.JavaScript;
            import org.gamboni.tech.web.ui.Element;
            import org.gamboni.tech.web.ui.Html;
            import org.gamboni.tech.web.ui.HtmlFragment;
            import org.gamboni.tech.web.ui.PageMember;

            import java.util.function.Function;

            import static org.gamboni.tech.web.js.JavaScript.*;

            public class TimeBasedTemplate {
                private final String id;
                private final Element base;

                private TimeBasedTemplate(String id, Element base) {
                    this.id = id;
                    this.base = base;
                }

                public static PageMember<Object, TimeBasedTemplate> create(Element base, Function<JsHtmlElement, JsFragment> updateLogic) {
                    return page -> {
                        String id = page.freshElementId("timer");
                        var updateFunction =
                        new JavaScript.Fun(page.freshElementId("updateTimer"));
                        page.addToScript(updateFunction.declare(seq(
                                updateLogic.apply(find(id)),
                                setTimeout(updateFunction.invoke(), 1000)
                        )));

                        page.addToOnLoad(onLoad -> updateFunction.invoke());

                        return new TimeBasedTemplate(
                                id,
                                base.withAttribute(Html.attribute("id", id)));
                    };
                }

                /**
                 * Return a JavaScript expression finding this element in the current document.
                 */
                private static JavaScript.JsHtmlElement find(String id) {
                    return getElementById(literal(id));
                }

                public HtmlFragment render() {
                    return base;
                }
            }
