package org.gamboni.mserver.ui;

import org.gamboni.mserver.MServerController;
import org.gamboni.mserver.data.DirectoryState;
import org.gamboni.mserver.data.Item;
import org.gamboni.mserver.data.PlayState;
import org.gamboni.mserver.tech.Mapping;
import org.gamboni.tech.web.ui.AbstractPage;
import org.gamboni.tech.web.ui.Css;
import org.gamboni.tech.web.ui.Html;
import org.gamboni.tech.web.ui.IdentifiedElement;

import java.io.File;
import java.util.List;

import static org.gamboni.tech.web.ui.Html.*;

public class DirectoryPage extends AbstractPage {

    private final MServerController controller;
    private final Mapping mapping;
    private final Style style;
    private final Script script;

    public final IdentifiedElement progress;

    public DirectoryPage(MServerController controller, Mapping mapping, Style style, Script script) {
        this.controller = controller;
        this.mapping = mapping;
        this.style = style;
        this.script = script;
        this.progress = setId("progress").to(div(List.of(style.progress)));

        script.setPage(this);
    }
    public final IdentifiedElement status = setId("status").to(p(escape("Loading…")));

    public String render(DirectoryState state, File folder, Iterable<Item> files) {
        return html(List.of(style, script), List.of(
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
                        ul(style.grid, files, style.item, item -> box(state.getFileState(item.file), item))
                )
        )
                .onLoad(script.doOnLoad(
                        mapping.fileToPath(folder),
                        state.getStamp()))
                .toString();
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
