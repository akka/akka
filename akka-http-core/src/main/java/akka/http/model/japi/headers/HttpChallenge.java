/**
 * Copyright (C) 2009-2014 Typesafe Inc. <http://www.typesafe.com>
 */

package akka.http.model.japi.headers;

import akka.http.model.headers.HttpChallenge$;
import akka.http.model.japi.Util;

import java.util.Map;

public abstract class HttpChallenge {
    public abstract String scheme();
    public abstract String realm();

    public abstract Map<String, String> getParams();

    public static HttpChallenge create(String scheme, String realm) {
        return new akka.http.model.headers.HttpChallenge(scheme, realm, Util.emptyMap);
    }
    public static HttpChallenge create(String scheme, String realm, Map<String, String> params) {
        return new akka.http.model.headers.HttpChallenge(scheme, realm, Util.convertMapToScala(params));
    }
}