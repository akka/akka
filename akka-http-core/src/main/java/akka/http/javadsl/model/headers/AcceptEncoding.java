/**
 * Copyright (C) 2009-2016 Lightbend Inc. <http://www.lightbend.com>
 */

package akka.http.javadsl.model.headers;

import akka.http.impl.util.Util;

/**
 *  Model for the `Accept-Encoding` header.
 *  Specification: http://tools.ietf.org/html/draft-ietf-httpbis-p2-semantics-26#section-5.3.4
 */
public abstract class AcceptEncoding extends akka.http.scaladsl.model.HttpHeader {
    public abstract Iterable<HttpEncodingRange> getEncodings();

    public static AcceptEncoding create(HttpEncoding encoding) {
        return new akka.http.scaladsl.model.headers.Accept$minusEncoding(Util.<HttpEncodingRange, akka.http.scaladsl.model.headers.HttpEncodingRange>convertArray(new HttpEncodingRange[]{encoding.toRange()}));
    }
    public static AcceptEncoding create(HttpEncodingRange... encodings) {
        return new akka.http.scaladsl.model.headers.Accept$minusEncoding(Util.<HttpEncodingRange, akka.http.scaladsl.model.headers.HttpEncodingRange>convertArray(encodings));
    }
}
