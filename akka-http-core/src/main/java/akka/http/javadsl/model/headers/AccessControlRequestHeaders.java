/**
 * Copyright (C) 2009-2016 Lightbend Inc. <http://www.lightbend.com>
 */

package akka.http.javadsl.model.headers;

/**
 *  Model for the `Access-Control-Request-Headers` header.
 *  Specification: http://www.w3.org/TR/cors/#access-control-request-headers-request-header
 */
public abstract class AccessControlRequestHeaders extends akka.http.scaladsl.model.HttpHeader {
    public abstract Iterable<String> getHeaders();

    public static AccessControlRequestHeaders create(String... headers) {
        return new akka.http.scaladsl.model.headers.Access$minusControl$minusRequest$minusHeaders(akka.http.impl.util.Util.<String, String>convertArray(headers));
    }
}
