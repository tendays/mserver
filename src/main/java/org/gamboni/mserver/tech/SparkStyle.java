package org.gamboni.mserver.tech;

import org.gamboni.tech.web.ui.Css;
import spark.Spark;

public abstract class SparkStyle extends Css {
    protected SparkStyle() {
        Spark.get(getUrl(), (req, res) -> {
            res.header("Content-Type", getMime());
            return this.render();
        });
    }
}
