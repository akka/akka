/**
 * Copyright (C) 2009-2016 Lightbend Inc. <http://www.lightbend.com>
 */

package akka.http.javadsl.model.headers;

import java.net.InetSocketAddress;

public abstract class Host extends akka.http.scaladsl.model.HttpHeader {

    public static Host create(InetSocketAddress address) {
        return akka.http.scaladsl.model.headers.Host.apply(address);
    }

    public static Host create(String host) {
        return akka.http.scaladsl.model.headers.Host.apply(host);
    }

    public static Host create(String host, int port) {
      return akka.http.scaladsl.model.headers.Host.apply(host, port);
    }

    public abstract akka.http.javadsl.model.Host host();
    public abstract int port();
}
