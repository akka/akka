/**
 * Copyright (C) 2009-2014 Typesafe Inc. <http://www.typesafe.com>
 */

package akka.http.model.japi;

/**
 * Represents a charset in Http. See {@link HttpCharsets} for a set of predefined charsets and
 * static constructors to create and register custom charsets.
 */
public abstract class HttpCharset {
    /**
     * Returns the name of this charset.
     */
    public abstract String value();

    /**
     * Creates a range from this charset with qValue = 1.
     */
    public HttpCharsetRange toRange() {
        return withQValue(1f);
    }

    /**
     * Creates a range from this charset with the given qValue.
     */
    public HttpCharsetRange toRange(float qValue) {
        return withQValue(qValue);
    }

    /**
     * An alias for toRange(float).
     */
    public abstract HttpCharsetRange withQValue(float qValue);

    /**
     * Returns the predefined (and registered) alias names for this charset.
     */
    public abstract Iterable<String> getAliases();
}
