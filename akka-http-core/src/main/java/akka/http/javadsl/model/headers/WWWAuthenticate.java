/**
 * Copyright (C) 2009-2016 Lightbend Inc. <http://www.lightbend.com>
 */

package akka.http.javadsl.model.headers;

/**
 *  Model for the `WWW-Authenticate` header.
 *  Specification: http://tools.ietf.org/html/draft-ietf-httpbis-p7-auth-26#section-4.1
 */
public abstract class WWWAuthenticate extends akka.http.scaladsl.model.HttpHeader {
    public abstract Iterable<HttpChallenge> getChallenges();

    public static WWWAuthenticate create(HttpChallenge... challenges) {
        return new akka.http.scaladsl.model.headers.WWW$minusAuthenticate(akka.http.impl.util.Util.<HttpChallenge, akka.http.scaladsl.model.headers.HttpChallenge>convertArray(challenges));
    }
}
