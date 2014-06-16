/**
 * Copyright (C) 2009-2014 Typesafe Inc. <http://www.typesafe.com>
 */

package akka.http.model.japi.headers;

/**
 *  Model for the `Access-Control-Request-Headers` header.
 *  Specification: http://www.w3.org/TR/cors/#access-control-request-headers-request-header
 */
public abstract class AccessControlRequestHeaders extends akka.http.model.HttpHeader {
    public abstract Iterable<String> getHeaders();

    public static AccessControlRequestHeaders create(String... headers) {
        return new akka.http.model.headers.Access$minusControl$minusRequest$minusHeaders(akka.http.model.japi.Util.<String, String>convertArray(headers));
    }
}
