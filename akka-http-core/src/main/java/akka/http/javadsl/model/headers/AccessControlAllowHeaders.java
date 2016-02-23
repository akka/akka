/**
 * Copyright (C) 2009-2016 Lightbend Inc. <http://www.lightbend.com>
 */

package akka.http.javadsl.model.headers;

/**
 *  Model for the `Access-Control-Allow-Headers` header.
 *  Specification: http://www.w3.org/TR/cors/#access-control-allow-headers-response-header
 */
public abstract class AccessControlAllowHeaders extends akka.http.scaladsl.model.HttpHeader {
    public abstract Iterable<String> getHeaders();

    public static AccessControlAllowHeaders create(String... headers) {
        return new akka.http.scaladsl.model.headers.Access$minusControl$minusAllow$minusHeaders(akka.http.impl.util.Util.<String, String>convertArray(headers));
    }
}
