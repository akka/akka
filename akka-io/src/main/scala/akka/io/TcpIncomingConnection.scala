/**
 * Copyright (C) 2009-2013 Typesafe Inc. <http://www.typesafe.com>
 */

package akka.io

import java.nio.channels.SocketChannel
import scala.collection.immutable
import akka.actor.ActorRef
import Tcp.SocketOption

/**
 * An actor handling the connection state machine for an incoming, already connected
 * SocketChannel.
 */
class TcpIncomingConnection(_selector: ActorRef,
                            _channel: SocketChannel,
                            handler: ActorRef,
                            options: immutable.Seq[SocketOption]) extends TcpConnection(_selector, _channel) {

  context.watch(handler) // sign death pact

  completeConnect(handler, options)

  def receive = PartialFunction.empty
}
