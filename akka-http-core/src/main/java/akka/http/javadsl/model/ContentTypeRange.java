/*
 * Copyright (C) 2009-2015 Typesafe Inc. <http://www.typesafe.com>
 */

package akka.http.javadsl.model;

/**
 * A data structure that combines an acceptable media range and an acceptable charset range into
 * one structure to be used with unmarshalling.
 */
public abstract class ContentTypeRange {
    public abstract MediaRange mediaRange();

    public abstract HttpCharsetRange charsetRange();

    /**
     * Returns true if this range includes the given content type.
     */
    public abstract boolean matches(ContentType contentType);

    /**
     * Returns a ContentType instance which fits this range.
     */
    public abstract ContentType specimen();
}
