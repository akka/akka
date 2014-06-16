/**
 * Copyright (C) 2009-2014 Typesafe Inc. <http://www.typesafe.com>
 */

package akka.http.model.japi.headers;

/**
 *  Model for the `Cookie` header.
 *  Specification: https://tools.ietf.org/html/rfc6265#section-4.2
 */
public abstract class Cookie extends akka.http.model.HttpHeader {
    public abstract Iterable<HttpCookie> getCookies();

    public static Cookie create(HttpCookie... cookies) {
        return new akka.http.model.headers.Cookie(akka.http.model.japi.Util.<HttpCookie, akka.http.model.headers.HttpCookie>convertArray(cookies));
    }
}
