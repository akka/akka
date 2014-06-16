/**
 * Copyright (C) 2009-2014 Typesafe Inc. <http://www.typesafe.com>
 */

package akka.http.model.japi.headers;

/**
 *  Model for the `Range` header.
 *  Specification: http://tools.ietf.org/html/draft-ietf-httpbis-p5-range-26#section-3.1
 */
public abstract class Range extends akka.http.model.HttpHeader {
    public abstract RangeUnit rangeUnit();
    public abstract Iterable<ByteRange> getRanges();

    public static Range create(RangeUnit rangeUnit, ByteRange... ranges) {
        return new akka.http.model.headers.Range(((akka.http.model.headers.RangeUnit) rangeUnit), akka.http.model.japi.Util.<ByteRange, akka.http.model.headers.ByteRange>convertArray(ranges));
    }
}
