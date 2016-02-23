/**
 * Copyright (C) 2009-2016 Lightbend Inc. <http://www.lightbend.com>
 */

package akka.http.javadsl.model.headers;

/**
 *  Model for the `Proxy-Authenticate` header.
 *  Specification: http://tools.ietf.org/html/draft-ietf-httpbis-p7-auth-26#section-4.3
 */
public abstract class ProxyAuthenticate extends akka.http.scaladsl.model.HttpHeader {
    public abstract Iterable<HttpChallenge> getChallenges();

    public static ProxyAuthenticate create(HttpChallenge... challenges) {
        return new akka.http.scaladsl.model.headers.Proxy$minusAuthenticate(akka.http.impl.util.Util.<HttpChallenge, akka.http.scaladsl.model.headers.HttpChallenge>convertArray(challenges));
    }
}
