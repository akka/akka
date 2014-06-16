/**
 * Copyright (C) 2009-2014 Typesafe Inc. <http://www.typesafe.com>
 */

package akka.http.model.japi.headers;

/**
 *  Model for the `Proxy-Authenticate` header.
 *  Specification: http://tools.ietf.org/html/draft-ietf-httpbis-p7-auth-26#section-4.3
 */
public abstract class ProxyAuthenticate extends akka.http.model.HttpHeader {
    public abstract Iterable<HttpChallenge> getChallenges();

    public static ProxyAuthenticate create(HttpChallenge... challenges) {
        return new akka.http.model.headers.Proxy$minusAuthenticate(akka.http.model.japi.Util.<HttpChallenge, akka.http.model.headers.HttpChallenge>convertArray(challenges));
    }
}
