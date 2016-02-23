/**
 * Copyright (C) 2009-2016 Lightbend Inc. <http://www.lightbend.com>
 */

package akka.http.javadsl.model;

import java.util.Map;

/**
 * Represents an Http media-range. A media-range either matches a single media-type
 * or it matches all media-types of a given main-type. Each range can specify a qValue
 * or other parameters.
 */
public abstract class MediaRange {
    /**
     * Returns the main-type this media-range matches.
     */
    public abstract String mainType();

    /**
     * Returns the qValue of this media-range.
     */
    public abstract float qValue();

    /**
     * Checks if this range matches a given media-type.
     */
    public abstract boolean matches(MediaType mediaType);

    /**
     * Returns a Map of the parameters of this media-range.
     */
    public abstract Map<String, String> getParams();

    /**
     * Returns a copy of this instance with a changed qValue.
     */
    public abstract MediaRange withQValue(float qValue);
}
