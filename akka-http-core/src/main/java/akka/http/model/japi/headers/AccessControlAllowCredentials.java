/**
 * Copyright (C) 2009-2014 Typesafe Inc. <http://www.typesafe.com>
 */

package akka.http.model.japi.headers;

/**
 *  Model for the `Access-Control-Allow-Credentials` header.
 *  Specification: http://www.w3.org/TR/cors/#access-control-allow-credentials-response-header
 */
public abstract class AccessControlAllowCredentials extends akka.http.model.HttpHeader {
    public abstract boolean allow();

    public static AccessControlAllowCredentials create(boolean allow) {
        return new akka.http.model.headers.Access$minusControl$minusAllow$minusCredentials(allow);
    }
}
