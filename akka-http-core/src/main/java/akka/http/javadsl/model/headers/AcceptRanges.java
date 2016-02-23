/**
 * Copyright (C) 2009-2016 Lightbend Inc. <http://www.lightbend.com>
 */

package akka.http.javadsl.model.headers;

/**
 *  Model for the `Accept-Ranges` header.
 *  Specification: http://tools.ietf.org/html/draft-ietf-httpbis-p5-range-26#section-2.3
 */
public abstract class AcceptRanges extends akka.http.scaladsl.model.HttpHeader {
    public abstract Iterable<RangeUnit> getRangeUnits();

    public static AcceptRanges create(RangeUnit... rangeUnits) {
        return new akka.http.scaladsl.model.headers.Accept$minusRanges(akka.http.impl.util.Util.<RangeUnit, akka.http.scaladsl.model.headers.RangeUnit>convertArray(rangeUnits));
    }
}
