package org.gamboni.mserver.ui;

import org.gamboni.mserver.tech.SparkStyle;

public class Style extends SparkStyle {
    public ClassName top;
    public ClassName progressBar;
    public ClassName progress;
    public ClassName grid;
    public ClassName item;
    public ClassName itemBody;
    public ClassName label;
    public ClassName thumb;

    /** An item with this style is queued for playing at a later time. */
    public ClassName queued;
    /** An item with this style is currently playing (or paused). */
    public ClassName nowPlaying;

    private static final String topHeight = "6em";

    @Override
    public String render() {
        var a = Properties.INSTANCE;
        return rule(top,

                Position.FIXED,
                a.width("100%"),
                a.height(topHeight),
                a.top("0em"),
                a.backgroundColor("white"),
                a.zIndex(10))
                +
                rule(progressBar,
                        a.border("1px solid"),
                        a.width("calc(100vw - 30px)"))
                +
                rule(progress,
                        a.height("3px"),
                        a.backgroundColor("red"))
                +
                rule(item,
                        Position.RELATIVE,
                        Cursor.POINTER,
                        a.height("6em"),
                        a.backgroundColor("#eee"),
                        a.margin("5px"),
                        a.width("6em"),
                        FontFamily.SANS,
                        FontVariantCaps.SMALL_CAPS,
                        Overflow.HIDDEN,
                        Display.INLINE_BLOCK)
                +
                rule(itemBody,
                        a.height("100%"),
                        Display.BLOCK)
                +
                rule(label,
                        Position.ABSOLUTE,
                        a.width("100%"),
                        a.backgroundColor("#eeea"))
                +
                rule(nowPlaying,
                        a.backgroundColor("#fcc"))
                +
                rule(nowPlaying.after(),
                        Position.ABSOLUTE,
                        a.top("0px"),
                        a.right("0px"),
                        a.backgroundColor("#fff"),
                        a.content("\"🎶\""))
                +
                rule(queued,
                        a.backgroundColor("#ccf"))
                +
                rule(queued.after(),
                        Position.ABSOLUTE,
                        a.top("0px"),
                        a.right("0px"),
                        a.content("\"⏳\""),
                        a.backgroundColor("#ccfa"))
                +
                rule(thumb,
                        a.left("0"),
                        a.maxWidth("100%"))
                +
                rule(grid, a.marginTop(topHeight));
    }
}
