/**
 * Copyright (C) 2009-2014 Typesafe Inc. <http://www.typesafe.com>
 */

package akka.http.model.japi.headers;

/**
 *  Model for the `Transfer-Encoding` header.
 *  Specification: http://tools.ietf.org/html/draft-ietf-httpbis-p1-messaging-26#section-3.3.1
 */
public abstract class TransferEncoding extends akka.http.model.HttpHeader {
    public abstract Iterable<akka.http.model.japi.TransferEncoding> getEncodings();

    public static TransferEncoding create(akka.http.model.japi.TransferEncoding... encodings) {
        return new akka.http.model.headers.Transfer$minusEncoding(akka.http.model.japi.Util.<akka.http.model.japi.TransferEncoding, akka.http.model.TransferEncoding>convertArray(encodings));
    }
}
