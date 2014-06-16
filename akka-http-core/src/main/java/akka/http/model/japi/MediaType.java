/**
 * Copyright (C) 2009-2014 Typesafe Inc. <http://www.typesafe.com>
 */

package akka.http.model.japi;

/**
 * Represents an Http media-type. A media-type consists of a main-type and a sub-type.
 */
public abstract class MediaType {
    /**
     * Returns the main-type of this media-type.
     */
    public abstract String mainType();

    /**
     * Returns the sub-type of this media-type.
     */
    public abstract String subType();

    /**
     * Creates a media-range from this media-type.
     */
    public MediaRange toRange() {
        return MediaRanges.create(this);
    }

    /**
     * Creates a media-range from this media-type with a given qValue.
     */
    public MediaRange toRange(float qValue) {
        return MediaRanges.create(this, qValue);
    }
}
