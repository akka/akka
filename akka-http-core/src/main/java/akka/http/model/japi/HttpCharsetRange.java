/**
 * Copyright (C) 2009-2014 Typesafe Inc. <http://www.typesafe.com>
 */

package akka.http.model.japi;

/**
 * Represents an Http charset range. This can either be `*` which matches all charsets or a specific
 * charset. {@link HttpCharsetRanges} contains static constructors for HttpCharsetRanges.
 */
public abstract class HttpCharsetRange {
    /**
     * Returns if this range matches all charsets.
     */
    public abstract boolean matchesAll();

    /**
     * The qValue for this range.
     */
    public abstract float qValue();

    /**
     * Returns if the given charset matches this range.
     */
    public abstract boolean matches(HttpCharset charset);

    /**
     * Returns a copy of this range with the given qValue.
     */
    public abstract HttpCharsetRange withQValue(float qValue);
}
