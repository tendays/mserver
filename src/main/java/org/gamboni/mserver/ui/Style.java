package org.gamboni.mserver.ui;

import org.gamboni.mserver.tech.SparkStyle;

public class Style extends SparkStyle {
    public ClassName top;
    public ClassName progressBar;
    public ClassName progress;
    public ClassName grid;
    public ClassName item;
    public ClassName thumb;

    private static final String topHeight = "6em";

    @Override
    public String render() {
        var a = Properties.INSTANCE;
        return rule(top,

                a.position("fixed"),
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
                        a.position("relative"),
                        a.cursor("pointer"),
                        a.height("6em"),
                        a.backgroundColor("#eee"),
                        a.margin("5px"),
                        a.width("6em"),
                        a.fontFamily("sans"),
                        a.fontVariantCaps("small-caps"),
                        a.overflow("hidden"),
                        a.display("inline-block"))
                +
                rule(thumb,
                        a.position("absolute"),
                        a.left("0"),
                        a.maxWidth("100%"))
                +
                rule(grid, a.marginTop(topHeight));
    }
}
