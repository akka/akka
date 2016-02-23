/**
 * Copyright (C) 2009-2016 Lightbend Inc. <http://www.lightbend.com>
 */

package akka.http.javadsl.model.headers;

/**
 *  Model for the `Content-Encoding` header.
 *  Specification: http://tools.ietf.org/html/draft-ietf-httpbis-p2-semantics-26#section-3.1.2.2
 */
public abstract class ContentEncoding extends akka.http.scaladsl.model.HttpHeader {
    public abstract Iterable<HttpEncoding> getEncodings();

    public static ContentEncoding create(HttpEncoding... encodings) {
        return new akka.http.scaladsl.model.headers.Content$minusEncoding(akka.http.impl.util.Util.<HttpEncoding, akka.http.scaladsl.model.headers.HttpEncoding>convertArray(encodings));
    }
}
