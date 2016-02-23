/**
 * Copyright (C) 2009-2016 Lightbend Inc. <http://www.lightbend.com>
 */

package akka.http.javadsl.model.headers;

/**
 *  Model for the `Set-Cookie` header.
 *  Specification: https://tools.ietf.org/html/rfc6265
 */
public abstract class SetCookie extends akka.http.scaladsl.model.HttpHeader {
    public abstract HttpCookie cookie();

    public static SetCookie create(HttpCookie cookie) {
        return new akka.http.scaladsl.model.headers.Set$minusCookie(((akka.http.scaladsl.model.headers.HttpCookie) cookie));
    }
}
