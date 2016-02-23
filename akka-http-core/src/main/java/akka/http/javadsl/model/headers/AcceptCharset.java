/**
 * Copyright (C) 2009-2016 Lightbend Inc. <http://www.lightbend.com>
 */

package akka.http.javadsl.model.headers;

import akka.http.impl.util.Util;
import akka.http.javadsl.model.HttpCharsetRange;

/**
 *  Model for the `Accept-Charset` header.
 *  Specification: http://tools.ietf.org/html/draft-ietf-httpbis-p2-semantics-26#section-5.3.3
 */
public abstract class AcceptCharset extends akka.http.scaladsl.model.HttpHeader {
    public abstract Iterable<HttpCharsetRange> getCharsetRanges();

    public static AcceptCharset create(HttpCharsetRange... charsetRanges) {
        return new akka.http.scaladsl.model.headers.Accept$minusCharset(Util.<HttpCharsetRange, akka.http.scaladsl.model.HttpCharsetRange>convertArray(charsetRanges));
    }
}
