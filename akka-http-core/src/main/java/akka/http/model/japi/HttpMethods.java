/**
 * Copyright (C) 2009-2014 Typesafe Inc. <http://www.typesafe.com>
 */

package akka.http.model.japi;

import akka.japi.Option;
import akka.http.model.HttpMethods$;

/**
 * Contains static constants for predefined method types.
 */
public final class HttpMethods {
    private HttpMethods() {}

    public static final HttpMethod CONNECT = akka.http.model.HttpMethods.CONNECT();
    public static final HttpMethod DELETE  = akka.http.model.HttpMethods.DELETE();
    public static final HttpMethod GET     = akka.http.model.HttpMethods.GET();
    public static final HttpMethod HEAD    = akka.http.model.HttpMethods.HEAD();
    public static final HttpMethod OPTIONS = akka.http.model.HttpMethods.OPTIONS();
    public static final HttpMethod PATCH   = akka.http.model.HttpMethods.PATCH();
    public static final HttpMethod POST    = akka.http.model.HttpMethods.POST();
    public static final HttpMethod PUT     = akka.http.model.HttpMethods.PUT();
    public static final HttpMethod TRACE   = akka.http.model.HttpMethods.TRACE();

    /**
     * Register a custom method type.
     */
    public static HttpMethod registerCustom(String value, boolean safe, boolean idempotent, boolean entityAccepted) {
        return akka.http.model.HttpMethods.register(akka.http.model.HttpMethod.custom(value, safe, idempotent, entityAccepted));
    }

    public static Option<HttpMethod> lookup(String name) {
        return Util.<HttpMethod, akka.http.model.HttpMethod>lookupInRegistry(HttpMethods$.MODULE$, name);
    }
}
