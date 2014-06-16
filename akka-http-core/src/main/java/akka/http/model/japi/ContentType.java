/**
 * Copyright (C) 2009-2014 Typesafe Inc. <http://www.typesafe.com>
 */

package akka.http.model.japi;

/**
 * Represents an Http content-type. A content-type consists of a media-type and an optional charset.
 */
public abstract class ContentType {
    /**
     * Returns the media-type of the this content-type.
     */
    public abstract MediaType mediaType();

    /**
     * Returns if this content-type defines a charset.
     */
    public abstract boolean isCharsetDefined();

    /**
     * Returns the charset of this content-type if there is one or throws an exception
     * otherwise.
     */
    public abstract HttpCharset getDefinedCharset();

    /**
     * Creates a content-type from a media-type and a charset.
     */
    public static ContentType create(MediaType mediaType, HttpCharset charset) {
        return akka.http.model.ContentType.apply((akka.http.model.MediaType) mediaType, (akka.http.model.HttpCharset) charset);
    }

    /**
     * Creates a content-type from a media-type without specifying a charset.
     */
    public static ContentType create(MediaType mediaType) {
        return akka.http.model.ContentType.apply((akka.http.model.MediaType) mediaType);
    }
}
