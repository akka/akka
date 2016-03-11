/**
 * Copyright (C) 2009-2016 Lightbend Inc. <http://www.lightbend.com>
 */

package akka.http.javadsl.model;

/**
 * Represents an Http charset range. This can either be `*` which matches all charsets or a specific
 * charset. {@link HttpCharsetRanges} contains static constructors for HttpCharsetRanges.
 *
 * @see HttpCharsetRanges for convenience access to often used values.
 */
public abstract class HttpCharsetRange {

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
