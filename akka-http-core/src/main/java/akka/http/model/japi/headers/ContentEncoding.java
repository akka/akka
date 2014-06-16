/**
 * Copyright (C) 2009-2014 Typesafe Inc. <http://www.typesafe.com>
 */

package akka.http.model.japi.headers;

/**
 *  Model for the `Content-Encoding` header.
 *  Specification: http://tools.ietf.org/html/draft-ietf-httpbis-p2-semantics-26#section-3.1.2.2
 */
public abstract class ContentEncoding extends akka.http.model.HttpHeader {
    public abstract Iterable<HttpEncoding> getEncodings();

    public static ContentEncoding create(HttpEncoding... encodings) {
        return new akka.http.model.headers.Content$minusEncoding(akka.http.model.japi.Util.<HttpEncoding, akka.http.model.headers.HttpEncoding>convertArray(encodings));
    }
}
