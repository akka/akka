/**
 * Copyright (C) 2009-2014 Typesafe Inc. <http://www.typesafe.com>
 */

package akka.http.model.japi.headers;

import akka.http.model.japi.HttpCharsetRange;

/**
 *  Model for the `Accept-Charset` header.
 *  Specification: http://tools.ietf.org/html/draft-ietf-httpbis-p2-semantics-26#section-5.3.3
 */
public abstract class AcceptCharset extends akka.http.model.HttpHeader {
    public abstract Iterable<HttpCharsetRange> getCharsetRanges();

    public static AcceptCharset create(HttpCharsetRange... charsetRanges) {
        return new akka.http.model.headers.Accept$minusCharset(akka.http.model.japi.Util.<HttpCharsetRange, akka.http.model.HttpCharsetRange>convertArray(charsetRanges));
    }
}
