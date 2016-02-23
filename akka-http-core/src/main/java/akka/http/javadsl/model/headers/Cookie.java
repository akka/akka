/**
 * Copyright (C) 2009-2016 Lightbend Inc. <http://www.lightbend.com>
 */

package akka.http.javadsl.model.headers;

/**
 *  Model for the `Cookie` header.
 *  Specification: https://tools.ietf.org/html/rfc6265#section-4.2
 */
public abstract class Cookie extends akka.http.scaladsl.model.HttpHeader {
    public abstract Iterable<HttpCookiePair> getCookies();

    public static Cookie create(HttpCookiePair... cookies) {
        return new akka.http.scaladsl.model.headers.Cookie(akka.http.impl.util.Util.<HttpCookiePair, akka.http.scaladsl.model.headers.HttpCookiePair>convertArray(cookies));
    }
    public static Cookie create(String name, String value) {
        return create(HttpCookiePair.create(name, value));
    }
}
