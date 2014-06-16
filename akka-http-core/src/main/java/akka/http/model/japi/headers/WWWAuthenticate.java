/**
 * Copyright (C) 2009-2014 Typesafe Inc. <http://www.typesafe.com>
 */

package akka.http.model.japi.headers;

/**
 *  Model for the `WWW-Authenticate` header.
 *  Specification: http://tools.ietf.org/html/draft-ietf-httpbis-p7-auth-26#section-4.1
 */
public abstract class WWWAuthenticate extends akka.http.model.HttpHeader {
    public abstract Iterable<HttpChallenge> getChallenges();

    public static WWWAuthenticate create(HttpChallenge... challenges) {
        return new akka.http.model.headers.WWW$minusAuthenticate(akka.http.model.japi.Util.<HttpChallenge, akka.http.model.headers.HttpChallenge>convertArray(challenges));
    }
}
