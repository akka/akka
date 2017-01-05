/**
 * Copyright (C) 2009-2016 Lightbend Inc. <http://www.lightbend.com>
 */

package akka.http.javadsl.model.headers;

import akka.http.impl.util.Util;
import akka.japi.Option;


import java.util.HashMap;
import java.util.Map;

public abstract class HttpChallenge {
    public abstract String scheme();
    public abstract String realm();

    public abstract Map<String, String> getParams();

    /**
     * @deprecated Use constructor with optional realm parameter instead.
     */
    @Deprecated
    public static HttpChallenge create(String scheme, String realm) {
        return akka.http.scaladsl.model.headers.HttpChallenge.apply(scheme, scala.Option.apply(realm), Util.emptyMap);
    }

    /**
     * @deprecated Use constructor with optional realm parameter instead.
     */
    @Deprecated
    public static HttpChallenge create(String scheme, String realm, Map<String, String> params) {
        return akka.http.scaladsl.model.headers.HttpChallenge.apply(scheme, scala.Option.apply(realm), Util.convertMapToScala(params));
    }

    public static HttpChallenge create(String scheme, Option<String> realm) {
        return akka.http.scaladsl.model.headers.HttpChallenge.apply(scheme, realm.asScala(), Util.emptyMap);
    }

    public static HttpChallenge create(String scheme, Option<String> realm, Map<String, String> params) {
        return akka.http.scaladsl.model.headers.HttpChallenge.apply(scheme, realm.asScala(), Util.convertMapToScala(params));
    }

    public static HttpChallenge createBasic(String realm) {
        Map<String, String> params = new HashMap<String, String>();
        params.put("charset", "UTF-8");
        return create("Basic", realm, params);
    }

    public static HttpChallenge createOAuth2(String realm) {
        return create("Bearer", realm);
    }
}
