/**
 * Copyright (C) 2009-2016 Lightbend Inc. <http://www.lightbend.com>
 */

package akka.http.javadsl.model.headers;

/**
 *  Model for the `Access-Control-Expose-Headers` header.
 *  Specification: http://www.w3.org/TR/cors/#access-control-expose-headers-response-header
 */
public abstract class AccessControlExposeHeaders extends akka.http.scaladsl.model.HttpHeader {
    public abstract Iterable<String> getHeaders();

    public static AccessControlExposeHeaders create(String... headers) {
        return new akka.http.scaladsl.model.headers.Access$minusControl$minusExpose$minusHeaders(akka.http.impl.util.Util.<String, String>convertArray(headers));
    }
}
