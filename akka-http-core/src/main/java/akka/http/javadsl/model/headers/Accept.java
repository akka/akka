/**
 * Copyright (C) 2009-2016 Lightbend Inc. <http://www.lightbend.com>
 */

package akka.http.javadsl.model.headers;

import akka.http.impl.util.Util;
import akka.http.javadsl.model.MediaRange;

/**
 *  Model for the `Accept` header.
 *  Specification: http://tools.ietf.org/html/draft-ietf-httpbis-p2-semantics-26#section-5.3.2
 */
public abstract class Accept extends akka.http.scaladsl.model.HttpHeader {
    public abstract Iterable<MediaRange> getMediaRanges();

    public abstract boolean acceptsAll();

    public static Accept create(MediaRange... mediaRanges) {
        return new akka.http.scaladsl.model.headers.Accept(Util.<MediaRange, akka.http.scaladsl.model.MediaRange>convertArray(mediaRanges));
    }
}
