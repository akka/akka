/**
 * Copyright (C) 2009-2016 Lightbend Inc. <http://www.lightbend.com>
 */

package akka.http.javadsl.model.headers;

/**
 *  Model for the `Transfer-Encoding` header.
 *  Specification: http://tools.ietf.org/html/draft-ietf-httpbis-p1-messaging-26#section-3.3.1
 */
public abstract class TransferEncoding extends akka.http.scaladsl.model.HttpHeader {
    public abstract Iterable<akka.http.javadsl.model.TransferEncoding> getEncodings();

    public static TransferEncoding create(akka.http.javadsl.model.TransferEncoding... encodings) {
        return new akka.http.scaladsl.model.headers.Transfer$minusEncoding(akka.http.impl.util.Util.<akka.http.javadsl.model.TransferEncoding, akka.http.scaladsl.model.TransferEncoding>convertArray(encodings));
    }
}
