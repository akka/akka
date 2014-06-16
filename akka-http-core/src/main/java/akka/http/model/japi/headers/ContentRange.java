/**
 * Copyright (C) 2009-2014 Typesafe Inc. <http://www.typesafe.com>
 */

package akka.http.model.japi.headers;

/**
 *  Model for the `Content-Range` header.
 *  Specification: http://tools.ietf.org/html/draft-ietf-httpbis-p5-range-26#section-4.2
 */
public abstract class ContentRange extends akka.http.model.HttpHeader {
    public abstract RangeUnit rangeUnit();
    public abstract akka.http.model.japi.ContentRange contentRange();

    public static ContentRange create(RangeUnit rangeUnit, akka.http.model.japi.ContentRange contentRange) {
        return new akka.http.model.headers.Content$minusRange(((akka.http.model.headers.RangeUnit) rangeUnit), ((akka.http.model.ContentRange) contentRange));
    }
}
