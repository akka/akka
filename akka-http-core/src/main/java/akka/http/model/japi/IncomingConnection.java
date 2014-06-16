/*
 * Copyright (C) 2009-2014 Typesafe Inc. <http://www.typesafe.com>
 */

package akka.http.model.japi;

import org.reactivestreams.api.Consumer;
import org.reactivestreams.api.Producer;

import java.net.InetSocketAddress;

/**
 * Represents one incoming connection.
 */
public interface IncomingConnection {
    /**
     * The address of the other peer.
     */
    InetSocketAddress remoteAddress();

    /**
     * A stream of requests coming in from the peer.
     */
    Producer<HttpRequest> getRequestProducer();

    /**
     * A consumer of HttpResponses to be sent to the peer.
     */
    Consumer<HttpResponse> getResponseConsumer();
}
