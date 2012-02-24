/**
 *   Copyright (C) 2011-2012 Typesafe Inc. <http://typesafe.com>
 */
package com.typesafe.config;

/**
 * The syntax of a character stream, <a href="http://json.org">JSON</a>, <a
 * href="https://github.com/typesafehub/config/blob/master/HOCON.md">HOCON</a>
 * aka ".conf", or <a href=
 * "http://download.oracle.com/javase/7/docs/api/java/util/Properties.html#load%28java.io.Reader%29"
 * >Java properties</a>.
 *
 */
public enum ConfigSyntax {
    /**
     * Pedantically strict <a href="http://json.org">JSON</a> format; no
     * comments, no unexpected commas, no duplicate keys in the same object.
     */
    JSON,
    /**
     * The JSON-superset <a
     * href="https://github.com/typesafehub/config/blob/master/HOCON.md"
     * >HOCON</a> format.
     */
    CONF,
    /**
     * Standard <a href=
     * "http://download.oracle.com/javase/7/docs/api/java/util/Properties.html#load%28java.io.Reader%29"
     * >Java properties</a> format.
     */
    PROPERTIES;
}
