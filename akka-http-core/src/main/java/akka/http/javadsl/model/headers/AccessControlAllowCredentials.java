/**
 * Copyright (C) 2009-2016 Lightbend Inc. <http://www.lightbend.com>
 */

package akka.http.javadsl.model.headers;

/**
 *  Model for the `Access-Control-Allow-Credentials` header.
 *  Specification: http://www.w3.org/TR/cors/#access-control-allow-credentials-response-header
 */
public abstract class AccessControlAllowCredentials extends akka.http.scaladsl.model.HttpHeader {
    public abstract boolean allow();

    public static AccessControlAllowCredentials create(boolean allow) {
        return new akka.http.scaladsl.model.headers.Access$minusControl$minusAllow$minusCredentials(allow);
    }
}
