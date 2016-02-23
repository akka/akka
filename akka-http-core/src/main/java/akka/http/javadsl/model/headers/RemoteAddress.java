/**
 * Copyright (C) 2009-2016 Lightbend Inc. <http://www.lightbend.com>
 */

package akka.http.javadsl.model.headers;

/**
 *  Model for the `Remote-Address` header.
 *  Custom header we use for optionally transporting the peer's IP in an HTTP header.
 */
public abstract class RemoteAddress extends akka.http.scaladsl.model.HttpHeader {
    public abstract akka.http.javadsl.model.RemoteAddress address();

    public static RemoteAddress create(akka.http.javadsl.model.RemoteAddress address) {
        return new akka.http.scaladsl.model.headers.Remote$minusAddress(((akka.http.scaladsl.model.RemoteAddress) address));
    }
}
