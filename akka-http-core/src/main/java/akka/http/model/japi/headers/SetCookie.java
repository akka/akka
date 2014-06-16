/**
 * Copyright (C) 2009-2014 Typesafe Inc. <http://www.typesafe.com>
 */

package akka.http.model.japi.headers;

/**
 *  Model for the `Set-Cookie` header.
 *  Specification: https://tools.ietf.org/html/rfc6265
 */
public abstract class SetCookie extends akka.http.model.HttpHeader {
    public abstract HttpCookie cookie();

    public static SetCookie create(HttpCookie cookie) {
        return new akka.http.model.headers.Set$minusCookie(((akka.http.model.headers.HttpCookie) cookie));
    }
}
