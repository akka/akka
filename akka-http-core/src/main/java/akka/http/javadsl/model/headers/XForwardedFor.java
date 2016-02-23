/**
 * Copyright (C) 2009-2016 Lightbend Inc. <http://www.lightbend.com>
 */

package akka.http.javadsl.model.headers;

/**
 *  Model for the `X-Forwarded-For` header.
 *  Specification: http://en.wikipedia.org/wiki/X-Forwarded-For
 */
public abstract class XForwardedFor extends akka.http.scaladsl.model.HttpHeader {
    public abstract Iterable<akka.http.javadsl.model.RemoteAddress> getAddresses();

    public static XForwardedFor create(akka.http.javadsl.model.RemoteAddress... addresses) {
        return new akka.http.scaladsl.model.headers.X$minusForwarded$minusFor(akka.http.impl.util.Util.<akka.http.javadsl.model.RemoteAddress, akka.http.scaladsl.model.RemoteAddress>convertArray(addresses));
    }
}
