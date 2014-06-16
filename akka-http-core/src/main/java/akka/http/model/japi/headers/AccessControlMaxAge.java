/**
 * Copyright (C) 2009-2014 Typesafe Inc. <http://www.typesafe.com>
 */

package akka.http.model.japi.headers;

/**
 *  Model for the `Access-Control-Max-Age` header.
 *  Specification: http://www.w3.org/TR/cors/#access-control-max-age-response-header
 */
public abstract class AccessControlMaxAge extends akka.http.model.HttpHeader {
    public abstract long deltaSeconds();

    public static AccessControlMaxAge create(long deltaSeconds) {
        return new akka.http.model.headers.Access$minusControl$minusMax$minusAge(deltaSeconds);
    }
}
