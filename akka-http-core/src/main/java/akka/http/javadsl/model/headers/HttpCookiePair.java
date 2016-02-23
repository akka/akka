/*
 * Copyright (C) 2009-2016 Lightbend Inc. <http://www.lightbend.com>
 */

package akka.http.javadsl.model.headers;

/**
 * Represents a cookie pair as used in the `Cookie` header as specified in
 * http://tools.ietf.org/search/rfc6265#section-4.2.1
 */
public abstract class HttpCookiePair {
    public abstract String name();
    public abstract String value();

    /**
     * Converts this cookie pair into an HttpCookie to be used with the
     * `Set-Cookie` header.
     */
    public abstract HttpCookie toCookie();

    public static HttpCookiePair create(String name, String value) {
        return akka.http.scaladsl.model.headers.HttpCookiePair.apply(name, value);
    }

    public static HttpCookiePair createRaw(String name, String value) {
        return akka.http.scaladsl.model.headers.HttpCookiePair.raw(name, value);
    }
}
