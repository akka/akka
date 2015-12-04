/**
 * Copyright (C) 2009-2014 Typesafe Inc. <http://www.typesafe.com>
 */

package akka.http.javadsl.model;

import akka.japi.Option;

/**
 * Represents an Http content-type. A content-type consists of a media-type and an optional charset.
 */
public interface ContentType {

    /**
     * The media-type of this content-type.
     */
    MediaType mediaType();

    /**
     * True if this ContentType is non-textual.
     */
    boolean binary();

    /**
     * Returns the charset if this ContentType is non-binary.
     */
    Option<HttpCharset> getCharsetOption();

    interface Binary extends ContentType {
    }

    interface NonBinary extends ContentType {
        HttpCharset charset();
    }

    interface WithFixedCharset extends NonBinary {
    }

    interface WithCharset extends NonBinary {
    }
}
