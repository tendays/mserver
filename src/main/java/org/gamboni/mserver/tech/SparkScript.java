package org.gamboni.mserver.tech;

import org.gamboni.tech.web.ui.AbstractScript;
import spark.Spark;

public abstract class SparkScript extends AbstractScript {
    protected SparkScript() {
        Spark.get(getUrl(), (req, res) -> {
            res.header("Content-Type", getMime());
            return this.render();
        });
    }
}
