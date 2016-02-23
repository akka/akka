/**
 * Copyright (C) 2009-2016 Lightbend Inc. <http://www.lightbend.com>
 */

package akka.http.javadsl.model.headers;

/**
 *  Model for the `X-Real-Ip` header.
 */
public abstract class XRealIp extends akka.http.scaladsl.model.HttpHeader {
    public abstract akka.http.javadsl.model.RemoteAddress address();

    public static XRealIp create(akka.http.javadsl.model.RemoteAddress address) {
        return new akka.http.scaladsl.model.headers.X$minusReal$minusIp(((akka.http.scaladsl.model.RemoteAddress) address));
    }
}
