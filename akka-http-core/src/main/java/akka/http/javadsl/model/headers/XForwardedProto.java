/**
 * Copyright (C) 2009-2017 Lightbend Inc. <http://www.lightbend.com>
 */

package akka.http.javadsl.model.headers;

/**
 *  Model for the `X-Forwarded-Proto` header.
 *  Specification: https://developer.mozilla.org/en-US/docs/Web/HTTP/Headers/X-Forwarded-Proto
 */
public abstract class XForwardedProto extends akka.http.scaladsl.model.HttpHeader {
    public abstract String getProtocol();

    public static XForwardedProto create(String protocol) {
        return new akka.http.scaladsl.model.headers.X$minusForwarded$minusProto(protocol);
    }
}
