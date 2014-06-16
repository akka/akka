/**
 * Copyright (C) 2009-2014 Typesafe Inc. <http://www.typesafe.com>
 */

package akka.http.model.japi.headers;

/**
 *  Model for the `X-Forwarded-For` header.
 *  Specification: http://en.wikipedia.org/wiki/X-Forwarded-For
 */
public abstract class XForwardedFor extends akka.http.model.HttpHeader {
    public abstract Iterable<akka.http.model.japi.RemoteAddress> getAddresses();

    public static XForwardedFor create(akka.http.model.japi.RemoteAddress... addresses) {
        return new akka.http.model.headers.X$minusForwarded$minusFor(akka.http.model.japi.Util.<akka.http.model.japi.RemoteAddress, akka.http.model.RemoteAddress>convertArray(addresses));
    }
}
