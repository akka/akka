/**
 * Copyright (C) 2009-2014 Typesafe Inc. <http://www.typesafe.com>
 */

package akka.http.javadsl.model;

import akka.japi.Option;

import java.net.InetAddress;
import java.net.InetSocketAddress;

public abstract class RemoteAddress {
    public abstract boolean isUnknown();

    public abstract Option<InetAddress> getAddress();

    /**
     * Returns a port if defined or 0 otherwise.
     */
    public abstract int getPort();

    public static final RemoteAddress UNKNOWN = akka.http.scaladsl.model.RemoteAddress.Unknown$.MODULE$;
    public static RemoteAddress create(InetAddress address) {
        return akka.http.scaladsl.model.RemoteAddress.apply(address, Option.none().asScala());
    }
    public static RemoteAddress create(InetSocketAddress address) {
        return akka.http.scaladsl.model.RemoteAddress.apply(address);
    }
    public static RemoteAddress create(String address) {
        return akka.http.scaladsl.model.RemoteAddress.apply(address);
    }
    public static RemoteAddress create(byte[] address) {
        return akka.http.scaladsl.model.RemoteAddress.apply(address);
    }
}
