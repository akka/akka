/**
 * Copyright (C) 2009-2013 Typesafe Inc. <http://www.typesafe.com>
 */

package akka.io

import java.nio.channels.SocketChannel
import scala.collection.immutable
import akka.actor.ActorRef
import akka.io.Inet.SocketOption

/**
 * An actor handling the connection state machine for an incoming, already connected
 * SocketChannel.
 *
 * INTERNAL API
 */
private[io] class TcpIncomingConnection(_tcp: TcpExt,
                                        _channel: SocketChannel,
                                        registry: ChannelRegistry,
                                        bindHandler: ActorRef,
                                        options: immutable.Traversable[SocketOption],
                                        readThrottling: Boolean)
  extends TcpConnection(_tcp, _channel, readThrottling) {

  context.watch(bindHandler) // sign death pact

  registry.register(channel, initialOps = 0)

  def receive = {
    case registration: ChannelRegistration ⇒ completeConnect(registration, bindHandler, options)
  }
}
