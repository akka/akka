/**
 * Copyright (C) 2009-2014 Typesafe Inc. <http://www.typesafe.com>
 */

package akka.http.model.japi.headers;

import akka.http.model.japi.MediaRange;

/**
 *  Model for the `Accept` header.
 *  Specification: http://tools.ietf.org/html/draft-ietf-httpbis-p2-semantics-26#section-5.3.2
 */
public abstract class Accept extends akka.http.model.HttpHeader {
    public abstract Iterable<MediaRange> getMediaRanges();

    public static Accept create(MediaRange... mediaRanges) {
        return new akka.http.model.headers.Accept(akka.http.model.japi.Util.<MediaRange, akka.http.model.MediaRange>convertArray(mediaRanges));
    }
}
