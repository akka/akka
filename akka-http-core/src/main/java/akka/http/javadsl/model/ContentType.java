/**
 * Copyright (C) 2009-2014 Typesafe Inc. <http://www.typesafe.com>
 */

package akka.http.javadsl.model;

import akka.japi.Option;

/**
 * Represents an Http content-type. A content-type consists of a media-type and an optional charset.
 */
public abstract class ContentType {
    /**
     * Returns the media-type of this content-type.
     */
    public abstract MediaType mediaType();

    /**
     * Returns the charset of this content-type.
     */
    public abstract HttpCharset charset();

    /**
     * Returns the optionally defined charset of this content-type.
     */
    public abstract Option<HttpCharset> getDefinedCharset();

    /**
     * Creates a content-type from a media-type and a charset.
     */
    public static ContentType create(MediaType mediaType, HttpCharset charset) {
        return akka.http.scaladsl.model.ContentType.apply((akka.http.scaladsl.model.MediaType) mediaType, (akka.http.scaladsl.model.HttpCharset) charset);
    }

    /**
     * Creates a content-type from a media-type without specifying a charset.
     */
    public static ContentType create(MediaType mediaType) {
        return akka.http.scaladsl.model.ContentType.apply((akka.http.scaladsl.model.MediaType) mediaType);
    }
}
