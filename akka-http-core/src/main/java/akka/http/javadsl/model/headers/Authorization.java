/**
 * Copyright (C) 2009-2016 Lightbend Inc. <http://www.lightbend.com>
 */

package akka.http.javadsl.model.headers;

/**
 *  Model for the `Authorization` header.
 *  Specification: http://tools.ietf.org/html/draft-ietf-httpbis-p7-auth-26#section-4.2
 */
public abstract class Authorization extends akka.http.scaladsl.model.HttpHeader {
    public abstract HttpCredentials credentials();

    public static Authorization create(HttpCredentials credentials) {
        return new akka.http.scaladsl.model.headers.Authorization(((akka.http.scaladsl.model.headers.HttpCredentials) credentials));
    }
    public static Authorization basic(String username, String password) {
        return create(HttpCredentials.createBasicHttpCredentials(username, password));
    }
    public static Authorization oauth2(String token) {
        return create(HttpCredentials.createOAuth2BearerToken(token));
    }
}
