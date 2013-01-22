/**
 * Copyright (C) 2009-2013 Typesafe Inc. <http://www.typesafe.com>
 */

package akka.io

import java.nio.channels.SocketChannel
import akka.actor.ActorRef
import Tcp.SocketOption

/**
 * An actor handling the connection state machine for an incoming, already connected
 * SocketChannel.
 */
private[io] class TcpIncomingConnection(_selector: ActorRef,
                                        _channel: SocketChannel,
                                        _tcp: TcpExt,
                                        handler: ActorRef,
                                        options: Traversable[SocketOption])
  extends TcpConnection(_selector, _channel, _tcp) {

  context.watch(handler) // sign death pact

  completeConnect(handler, options)

  def receive = PartialFunction.empty
}
