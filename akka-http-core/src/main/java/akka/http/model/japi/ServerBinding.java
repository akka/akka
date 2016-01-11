/*
 * Copyright (C) 2009-2014 Typesafe Inc. <http://www.typesafe.com>
 */

package akka.http.model.japi;

import org.reactivestreams.Publisher;

import java.io.Closeable;
import java.net.InetSocketAddress;

/**
 * The binding of a server. Allows access to its own address and to the stream
 * of incoming connections.
 */
public interface ServerBinding extends Closeable {
    /**
     * The local address this server is listening on.
     */
    InetSocketAddress localAddress();

    /**
     * The stream of incoming connections. The binding is solved and the listening
     * socket closed as soon as all subscriber of this streams have cancelled their
     * subscription.
     */
    Publisher<IncomingConnection> getConnectionStream();
}
