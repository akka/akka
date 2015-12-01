/**
 * Copyright (C) 2009-2014 Typesafe Inc. <http://www.typesafe.com>
 */

package akka.http.javadsl.model;

/**
 * Represents an Http media-type. A media-type consists of a main-type and a sub-type.
 */
public interface MediaType {

    /**
     * The main-type of this media-type.
     */
    String mainType();

    /**
     * The sub-type of this media-type.
     */
    String subType();

    /**
     * True when this media-type is generally compressible.
     */
    boolean compressible();

    /**
     * True when this media-type is not character-based.
     */
    boolean binary();

    boolean isApplication();
    boolean isAudio();
    boolean isImage();
    boolean isMessage();
    boolean isMultipart();
    boolean isText();
    boolean isVideo();

    /**
     * Creates a media-range from this media-type.
     */
    MediaRange toRange();

    /**
     * Creates a media-range from this media-type with a given qValue.
     */
    MediaRange toRange(float qValue);

    interface Binary extends MediaType {
        ContentType.Binary toContentType();
    }

    interface NonBinary extends MediaType {
    }

    interface WithFixedCharset extends NonBinary {
        ContentType.WithFixedCharset toContentType();
    }

    interface WithOpenCharset extends NonBinary {
        ContentType.WithCharset toContentType(HttpCharset charset);
    }

    interface Multipart extends WithOpenCharset {
    }
}
