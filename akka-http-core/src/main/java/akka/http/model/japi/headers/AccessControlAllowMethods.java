/**
 * Copyright (C) 2009-2014 Typesafe Inc. <http://www.typesafe.com>
 */

package akka.http.model.japi.headers;

import akka.http.model.japi.HttpMethod;

/**
 *  Model for the `Access-Control-Allow-Methods` header.
 *  Specification: http://www.w3.org/TR/cors/#access-control-allow-methods-response-header
 */
public abstract class AccessControlAllowMethods extends akka.http.model.HttpHeader {
    public abstract Iterable<HttpMethod> getMethods();

    public static AccessControlAllowMethods create(HttpMethod... methods) {
        return new akka.http.model.headers.Access$minusControl$minusAllow$minusMethods(akka.http.model.japi.Util.<HttpMethod, akka.http.model.HttpMethod>convertArray(methods));
    }
}
