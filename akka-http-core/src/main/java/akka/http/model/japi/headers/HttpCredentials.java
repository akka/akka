/**
 * Copyright (C) 2009-2014 Typesafe Inc. <http://www.typesafe.com>
 */

package akka.http.model.japi.headers;

import akka.http.model.japi.Util;

import java.util.Map;

public abstract class HttpCredentials {
    public abstract String scheme();
    public abstract String token();

    public abstract Map<String, String> getParams();

    public static HttpCredentials create(String scheme, String token) {
        return new akka.http.model.headers.GenericHttpCredentials(scheme, token, Util.emptyMap);
    }
    public static HttpCredentials create(String scheme, String token, Map<String, String> params) {
        return new akka.http.model.headers.GenericHttpCredentials(scheme, token, Util.convertMapToScala(params));
    }
    public static BasicHttpCredentials createBasicHttpCredential(String username, String password) {
        return new akka.http.model.headers.BasicHttpCredentials(username, password);
    }
    public static OAuth2BearerToken createOAuth2BearerToken(String token) {
        return new akka.http.model.headers.OAuth2BearerToken(token);
    }
}
