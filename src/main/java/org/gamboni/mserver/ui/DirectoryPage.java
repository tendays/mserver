package org.gamboni.mserver.ui;

import lombok.RequiredArgsConstructor;
import org.gamboni.mserver.MServerController;
import org.gamboni.mserver.data.Item;
import org.gamboni.tech.web.ui.AbstractPage;
import org.gamboni.tech.web.ui.Html;
import org.gamboni.tech.web.ui.IdentifiedElement;

import java.util.List;

import static org.gamboni.tech.web.js.JavaScript.literal;
import static org.gamboni.tech.web.ui.Html.*;

@RequiredArgsConstructor
public class DirectoryPage extends AbstractPage {

    private final MServerController controller;
    private final Style style;
    private final Script script;

    public final IdentifiedElement progress = setId("progress").to(div());
    public final IdentifiedElement status = setId("status").to(p(escape("Loading…")));

    public String render(String relativePath, Iterable<Item> files) {
        return html(List.of(style, script), List.of(
                        div(List.of(style.top),
                                p(
                                        button("Play/Pause", controller.pause),
                                        escape(" "),
                                        button("Stop", controller.stop)),
                                status,
                                div(List.of(style.progressBar),
                                        progress)),
                        ul(style.grid, files, style.item, file -> box(relativePath, file))
                )
        )
                .onLoad(script.showStatus.invoke())
                .toString();
    }

    private Html box(String relativePath, Item item) {
        if (item.isDirectory()) {
            return a(style.item, "./"+ item.name +"/", escape(item.name), thumb(item));
        } else if (item.isMusic()) {
            return div(List.of(
                    style.item,
                    attribute("onclick",
                            controller.play(literal(relativePath+ item.name)))),
                    escape(item.friendlyName()),
                    thumb(item));
        } else {
            return EMPTY;
        }
    }

    private Html thumb(Item item) {
        return item.getThumbnailPath()
                .map(path -> img(style.thumb, path))
                .orElse(Html.EMPTY);
    }
}
