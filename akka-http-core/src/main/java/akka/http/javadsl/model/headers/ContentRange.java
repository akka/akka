/**
 * Copyright (C) 2009-2016 Lightbend Inc. <http://www.lightbend.com>
 */

package akka.http.javadsl.model.headers;

/**
 *  Model for the `Content-Range` header.
 *  Specification: http://tools.ietf.org/html/draft-ietf-httpbis-p5-range-26#section-4.2
 */
public abstract class ContentRange extends akka.http.scaladsl.model.HttpHeader {
    public abstract RangeUnit rangeUnit();
    public abstract akka.http.javadsl.model.ContentRange contentRange();

    public static ContentRange create(RangeUnit rangeUnit, akka.http.javadsl.model.ContentRange contentRange) {
        return new akka.http.scaladsl.model.headers.Content$minusRange(((akka.http.scaladsl.model.headers.RangeUnit) rangeUnit), ((akka.http.scaladsl.model.ContentRange) contentRange));
    }
}
