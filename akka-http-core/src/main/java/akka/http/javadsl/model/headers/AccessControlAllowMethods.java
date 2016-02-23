/**
 * Copyright (C) 2009-2016 Lightbend Inc. <http://www.lightbend.com>
 */

package akka.http.javadsl.model.headers;

import akka.http.javadsl.model.HttpMethod;

/**
 *  Model for the `Access-Control-Allow-Methods` header.
 *  Specification: http://www.w3.org/TR/cors/#access-control-allow-methods-response-header
 */
public abstract class AccessControlAllowMethods extends akka.http.scaladsl.model.HttpHeader {
    public abstract Iterable<HttpMethod> getMethods();

    public static AccessControlAllowMethods create(HttpMethod... methods) {
        return new akka.http.scaladsl.model.headers.Access$minusControl$minusAllow$minusMethods(akka.http.impl.util.Util.<HttpMethod, akka.http.scaladsl.model.HttpMethod>convertArray(methods));
    }
}
