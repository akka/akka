/**
 *   Copyright (C) 2011-2012 Typesafe Inc. <http://typesafe.com>
 */
package com.typesafe.config;

import java.net.URL;
import java.util.List;


/**
 * Represents the origin (such as filename and line number) of a
 * {@link ConfigValue} for use in error messages. Obtain the origin of a value
 * with {@link ConfigValue#origin}. Exceptions may have an origin, see
 * {@link ConfigException#origin}, but be careful because
 * <code>ConfigException.origin()</code> may return null.
 *
 * <p>
 * It's best to use this interface only for debugging; its accuracy is
 * "best effort" rather than guaranteed, and a potentially-noticeable amount of
 * memory could probably be saved if origins were not kept around, so in the
 * future there might be some option to discard origins.
 *
 * <p>
 * <em>Do not implement this interface</em>; it should only be implemented by
 * the config library. Arbitrary implementations will not work because the
 * library internals assume a specific concrete implementation. Also, this
 * interface is likely to grow new methods over time, so third-party
 * implementations will break.
 */
public interface ConfigOrigin {
    /**
     * Returns a string describing the origin of a value or exception. This will
     * never return null.
     *
     * @return string describing the origin
     */
    public String description();

    /**
     * Returns a filename describing the origin. This will return null if the
     * origin was not a file.
     *
     * @return filename of the origin or null
     */
    public String filename();

    /**
     * Returns a URL describing the origin. This will return null if the origin
     * has no meaningful URL.
     *
     * @return url of the origin or null
     */
    public URL url();

    /**
     * Returns a classpath resource name describing the origin. This will return
     * null if the origin was not a classpath resource.
     *
     * @return resource name of the origin or null
     */
    public String resource();

    /**
     * Returns a line number where the value or exception originated. This will
     * return -1 if there's no meaningful line number.
     *
     * @return line number or -1 if none is available
     */
    public int lineNumber();

    /**
     * Returns any comments that appeared to "go with" this place in the file.
     * Often an empty list, but never null. The details of this are subject to
     * change, but at the moment comments that are immediately before an array
     * element or object field, with no blank line after the comment, "go with"
     * that element or field.
     * 
     * @return any comments that seemed to "go with" this origin, empty list if
     *         none
     */
    public List<String> comments();
}
