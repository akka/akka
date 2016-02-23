/**
 * Copyright (C) 2009-2016 Lightbend Inc. <http://www.lightbend.com>
 */

package akka.http.javadsl.model.headers;

import akka.http.javadsl.model.HttpMethod;

/**
 *  Model for the `Access-Control-Request-Method` header.
 *  Specification: http://www.w3.org/TR/cors/#access-control-request-method-request-header
 */
public abstract class AccessControlRequestMethod extends akka.http.scaladsl.model.HttpHeader {
    public abstract HttpMethod method();

    public static AccessControlRequestMethod create(HttpMethod method) {
        return new akka.http.scaladsl.model.headers.Access$minusControl$minusRequest$minusMethod(((akka.http.scaladsl.model.HttpMethod) method));
    }
}
