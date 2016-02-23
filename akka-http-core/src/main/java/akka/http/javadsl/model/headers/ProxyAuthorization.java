/**
 * Copyright (C) 2009-2016 Lightbend Inc. <http://www.lightbend.com>
 */

package akka.http.javadsl.model.headers;

/**
 *  Model for the `Proxy-Authorization` header.
 *  Specification: http://tools.ietf.org/html/draft-ietf-httpbis-p7-auth-26#section-4.4
 */
public abstract class ProxyAuthorization extends akka.http.scaladsl.model.HttpHeader {
    public abstract HttpCredentials credentials();

    public static ProxyAuthorization create(HttpCredentials credentials) {
        return new akka.http.scaladsl.model.headers.Proxy$minusAuthorization(((akka.http.scaladsl.model.headers.HttpCredentials) credentials));
    }
}
