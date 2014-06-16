/**
 * Copyright (C) 2009-2014 Typesafe Inc. <http://www.typesafe.com>
 */

package akka.http.model.japi.headers;

/**
 *  Model for the `Access-Control-Allow-Headers` header.
 *  Specification: http://www.w3.org/TR/cors/#access-control-allow-headers-response-header
 */
public abstract class AccessControlAllowHeaders extends akka.http.model.HttpHeader {
    public abstract Iterable<String> getHeaders();

    public static AccessControlAllowHeaders create(String... headers) {
        return new akka.http.model.headers.Access$minusControl$minusAllow$minusHeaders(akka.http.model.japi.Util.<String, String>convertArray(headers));
    }
}
