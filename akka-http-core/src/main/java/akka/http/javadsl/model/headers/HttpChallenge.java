/**
 * Copyright (C) 2009-2016 Lightbend Inc. <http://www.lightbend.com>
 */

package akka.http.javadsl.model.headers;

import akka.http.impl.util.Util;

import java.util.Map;

public abstract class HttpChallenge {
    public abstract String scheme();
    public abstract String realm();

    public abstract Map<String, String> getParams();

    public static HttpChallenge create(String scheme, String realm) {
        return new akka.http.scaladsl.model.headers.HttpChallenge(scheme, realm, Util.emptyMap);
    }
    public static HttpChallenge create(String scheme, String realm, Map<String, String> params) {
        return new akka.http.scaladsl.model.headers.HttpChallenge(scheme, realm, Util.convertMapToScala(params));
    }
}