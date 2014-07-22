/*
 * Copyright (C) 2009-2014 Typesafe Inc. <http://www.typesafe.com>
 */

package akka.http.model.japi;

import org.reactivestreams.Subscriber;
import org.reactivestreams.Publisher;

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
    Publisher<HttpRequest> getRequestPublisher();

    /**
     * A subscriber of HttpResponses to be sent to the peer.
     */
    Subscriber<HttpResponse> getResponseSubscriber();
}
