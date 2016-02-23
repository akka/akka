/**
 * Copyright (C) 2009-2016 Lightbend Inc. <http://www.lightbend.com>
 */

package akka.http.javadsl.model.headers;

/**
 *  Model for the `Range` header.
 *  Specification: http://tools.ietf.org/html/draft-ietf-httpbis-p5-range-26#section-3.1
 */
public abstract class Range extends akka.http.scaladsl.model.HttpHeader {
    public abstract RangeUnit rangeUnit();
    public abstract Iterable<ByteRange> getRanges();

    public static Range create(RangeUnit rangeUnit, ByteRange... ranges) {
        return new akka.http.scaladsl.model.headers.Range(((akka.http.scaladsl.model.headers.RangeUnit) rangeUnit), akka.http.impl.util.Util.<ByteRange, akka.http.scaladsl.model.headers.ByteRange>convertArray(ranges));
    }
}
