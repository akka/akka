/**
 * Copyright (C) 2009-2016 Lightbend Inc. <http://www.lightbend.com>
 */

package akka.http.javadsl.model.headers;

/**
 *  Model for the `Access-Control-Max-Age` header.
 *  Specification: http://www.w3.org/TR/cors/#access-control-max-age-response-header
 */
public abstract class AccessControlMaxAge extends akka.http.scaladsl.model.HttpHeader {
    public abstract long deltaSeconds();

    public static AccessControlMaxAge create(long deltaSeconds) {
        return new akka.http.scaladsl.model.headers.Access$minusControl$minusMax$minusAge(deltaSeconds);
    }
}
