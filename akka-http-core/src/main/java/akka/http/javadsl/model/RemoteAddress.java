/**
 * Copyright (C) 2009-2016 Lightbend Inc. <http://www.lightbend.com>
 */

package akka.http.javadsl.model;

import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.util.Optional;

import akka.http.javadsl.model.headers.HttpEncodingRanges;
import scala.compat.java8.OptionConverters;

public abstract class RemoteAddress {
    public abstract boolean isUnknown();

    public abstract Optional<InetAddress> getAddress();

    /**
     * Returns a port if defined or 0 otherwise.
     */
    public abstract int getPort();

    public static RemoteAddress create(InetAddress address) {
        return akka.http.scaladsl.model.RemoteAddress.apply(address, OptionConverters.toScala(Optional.empty()));
    }
    public static RemoteAddress create(InetSocketAddress address) {
        return akka.http.scaladsl.model.RemoteAddress.apply(address);
    }
    public static RemoteAddress create(byte[] address) {
        return akka.http.scaladsl.model.RemoteAddress.apply(address);
    }

    /**
     * @deprecated because of troublesome initialisation order (with regards to scaladsl class implementing this class).
     *             In some edge cases this field could end up containing a null value.
     *             Will be removed in Akka 3.x, use {@link RemoteAddresses#UNKNOWN} instead.
     */
    @Deprecated
    // FIXME: Remove in Akka 3.0
    public static final RemoteAddress UNKNOWN = RemoteAddresses.UNKNOWN;
}
