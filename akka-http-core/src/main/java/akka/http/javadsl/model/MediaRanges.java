/**
 * Copyright (C) 2009-2016 Lightbend Inc. <http://www.lightbend.com>
 */

package akka.http.javadsl.model;

import akka.http.impl.util.Util;

import java.util.Map;

/**
 * Contains a set of predefined media-ranges and static methods to create custom ones.
 */
public final class MediaRanges {
    private MediaRanges() {}

    public static final MediaRange ALL             = akka.http.scaladsl.model.MediaRanges.$times$div$times();
    public static final MediaRange ALL_APPLICATION = akka.http.scaladsl.model.MediaRanges.application$div$times();
    public static final MediaRange ALL_AUDIO       = akka.http.scaladsl.model.MediaRanges.audio$div$times();
    public static final MediaRange ALL_IMAGE       = akka.http.scaladsl.model.MediaRanges.image$div$times();
    public static final MediaRange ALL_MESSAGE     = akka.http.scaladsl.model.MediaRanges.message$div$times();
    public static final MediaRange ALL_MULTIPART   = akka.http.scaladsl.model.MediaRanges.multipart$div$times();
    public static final MediaRange ALL_TEXT        = akka.http.scaladsl.model.MediaRanges.text$div$times();
    public static final MediaRange ALL_VIDEO       = akka.http.scaladsl.model.MediaRanges.video$div$times();

    /**
     * Creates a custom universal media-range for a given main-type.
     */
    public static MediaRange create(MediaType mediaType) {
        return akka.http.scaladsl.model.MediaRange.apply((akka.http.scaladsl.model.MediaType) mediaType);
    }

    /**
     * Creates a custom universal media-range for a given main-type and a Map of parameters.
     */
    public static MediaRange custom(String mainType, Map<String, String> parameters) {
        return akka.http.scaladsl.model.MediaRange.custom(mainType, Util.convertMapToScala(parameters), 1.0f);
    }

    /**
     * Creates a custom universal media-range for a given main-type and qValue.
     */
    public static MediaRange create(MediaType mediaType, float qValue) {
        return akka.http.scaladsl.model.MediaRange.apply((akka.http.scaladsl.model.MediaType) mediaType, qValue);
    }
}
