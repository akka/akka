/**
 * Copyright (C) 2009-2016 Lightbend Inc. <http://www.lightbend.com>
 */

package akka.http.javadsl.model.headers;

import akka.http.impl.util.Util;

import java.util.Map;

public abstract class HttpCredentials {
    public abstract String scheme();
    public abstract String token();

    public abstract Map<String, String> getParams();

    public static HttpCredentials create(String scheme, String token) {
        return new akka.http.scaladsl.model.headers.GenericHttpCredentials(scheme, token, Util.emptyMap);
    }
    public static HttpCredentials create(String scheme, String token, Map<String, String> params) {
        return new akka.http.scaladsl.model.headers.GenericHttpCredentials(scheme, token, Util.convertMapToScala(params));
    }
    public static BasicHttpCredentials createBasicHttpCredentials(String username, String password) {
        return new akka.http.scaladsl.model.headers.BasicHttpCredentials(username, password);
    }
    public static OAuth2BearerToken createOAuth2BearerToken(String token) {
        return new akka.http.scaladsl.model.headers.OAuth2BearerToken(token);
    }
}
