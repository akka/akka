/*
 * Copyright (C) 2009-2018 Lightbend Inc. <https://www.lightbend.com>
 */

package akka.http.javadsl.model.headers;

/**
 *  Model for the `X-Forwarded-Host` header.
 *  Specification: https://developer.mozilla.org/en-US/docs/Web/HTTP/Headers/X-Forwarded-Host
 */
public abstract class XForwardedHost extends akka.http.scaladsl.model.HttpHeader {
    public abstract akka.http.javadsl.model.Host getHost();

    public static XForwardedHost create(akka.http.javadsl.model.Host host) {
        return new akka.http.scaladsl.model.headers.X$minusForwarded$minusHost(((akka.http.scaladsl.model.Uri.Host) host));
    }
}
