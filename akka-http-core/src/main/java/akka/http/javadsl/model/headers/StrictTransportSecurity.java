/**
 * Copyright (C) 2009-2016 Lightbend Inc. <http://www.lightbend.com>
 */

package akka.http.javadsl.model.headers;

/**
 *  Model for the `Strict-Transport-Security` header.
 *  Specification: https://tools.ietf.org/html/rfc6797
 */
public abstract class StrictTransportSecurity extends akka.http.scaladsl.model.HttpHeader {
    public abstract long maxAge();
    public abstract boolean includeSubDomains();

    public static StrictTransportSecurity create(long maxAge) {
        return new akka.http.scaladsl.model.headers.Strict$minusTransport$minusSecurity(maxAge, false);
    }
    public static StrictTransportSecurity create(long maxAge, boolean includeSubDomains) {
        return new akka.http.scaladsl.model.headers.Strict$minusTransport$minusSecurity(maxAge, includeSubDomains);
    }
}
