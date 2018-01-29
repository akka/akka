/*
 * Copyright (C) 2009-2018 Lightbend Inc. <https://www.lightbend.com>
 */

package akka.http.javadsl.model;

/**
 * Contains constructors to create a HttpCharsetRange.
 */
public final class HttpCharsetRanges {
    private HttpCharsetRanges() {}

    /**
     * A constant representing the range that matches all charsets.
     */
    public static final HttpCharsetRange ALL = akka.http.scaladsl.model.HttpCharsetRange.$times$.MODULE$;
}
