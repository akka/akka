/**
 *   Copyright (C) 2011 Typesafe Inc. <http://typesafe.com>
 */
package com.typesafe.config;


/**
 * A ConfigIncludeContext is passed to a ConfigIncluder. This interface is not
 * intended for apps to implement.
 */
public interface ConfigIncludeContext {
    /**
     * Tries to find a name relative to whatever is doing the including, for
     * example in the same directory as the file doing the including. Returns
     * null if it can't meaningfully create a relative name. The returned
     * parseable may not exist; this function is not required to do any IO, just
     * compute what the name would be.
     * 
     * The passed-in filename has to be a complete name (with extension), not
     * just a basename. (Include statements in config files are allowed to give
     * just a basename.)
     *
     * @param filename
     *            the name to make relative to the resource doing the including
     * @return parseable item relative to the resource doing the including, or
     *         null
     */
    ConfigParseable relativeTo(String filename);
}
