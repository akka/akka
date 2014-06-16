/**
 * Copyright (C) 2009-2014 Typesafe Inc. <http://www.typesafe.com>
 */

package akka.http.model.japi.headers;

/**
 *  Model for the `Remote-Address` header.
 *  Custom header we use for optionally transporting the peer's IP in an HTTP header.
 */
public abstract class RemoteAddress extends akka.http.model.HttpHeader {
    public abstract akka.http.model.japi.RemoteAddress address();

    public static RemoteAddress create(akka.http.model.japi.RemoteAddress address) {
        return new akka.http.model.headers.Remote$minusAddress(((akka.http.model.RemoteAddress) address));
    }
}
