/**
 * Copyright (C) 2009-2014 Typesafe Inc. <http://www.typesafe.com>
 */

package akka.http.model.japi.headers;

/**
 *  Model for the `Origin` header.
 *  Specification: http://tools.ietf.org/html/rfc6454#section-7
 */
public abstract class Origin extends akka.http.model.HttpHeader {
    public abstract Iterable<HttpOrigin> getOrigins();

    public static Origin create(HttpOrigin... origins) {
        return new akka.http.model.headers.Origin(akka.http.model.japi.Util.<HttpOrigin, akka.http.model.headers.HttpOrigin>convertArray(origins));
    }
}
