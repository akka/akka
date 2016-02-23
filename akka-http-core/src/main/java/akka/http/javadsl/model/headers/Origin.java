/**
 * Copyright (C) 2009-2016 Lightbend Inc. <http://www.lightbend.com>
 */

package akka.http.javadsl.model.headers;

/**
 *  Model for the `Origin` header.
 *  Specification: http://tools.ietf.org/html/rfc6454#section-7
 */
public abstract class Origin extends akka.http.scaladsl.model.HttpHeader {
    public abstract Iterable<HttpOrigin> getOrigins();

    public static Origin create(HttpOrigin... origins) {
        return new akka.http.scaladsl.model.headers.Origin(akka.http.impl.util.Util.<HttpOrigin, akka.http.scaladsl.model.headers.HttpOrigin>convertArray(origins));
    }
}
