/**
 * Copyright (C) 2009-2016 Lightbend Inc. <http://www.lightbend.com>
 */

package akka.http.javadsl.model.headers;

/**
 *  Model for the `Raw-Request-URI` header.
 *  Custom header we use for transporting the raw request URI either to the application (server-side)
 *  or to the request rendering stage (client-side).
 */
public abstract class RawRequestURI extends akka.http.scaladsl.model.HttpHeader {
    public abstract String uri();

    public static RawRequestURI create(String uri) {
        return new akka.http.scaladsl.model.headers.Raw$minusRequest$minusURI(uri);
    }
}
