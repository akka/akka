package com.typesafe.config;

import java.net.URL;

/** An opaque handle to something that can be parsed. */
public interface ConfigParseable {
    /**
     * Parse whatever it is.
     * 
     * @param options
     *            parse options, should be based on the ones from options()
     */
    ConfigObject parse(ConfigParseOptions options);

    /** Possibly return a URL representing the resource; this may return null. */
    URL url();

    /** Get the initial options, which can be modified then passed to parse(). */
    ConfigParseOptions options();
}
