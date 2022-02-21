/*
 * Copyright (C) 2009-2022 Lightbend Inc. <https://www.lightbend.com>
 */

package akka.io

import java.nio.channels.SocketChannel

import scala.collection.immutable

import scala.annotation.nowarn

import akka.actor.ActorRef
import akka.io.Inet.SocketOption

/**
 * An actor handling the connection state machine for an incoming, already connected
 * SocketChannel.
 *
 * INTERNAL API
 */
@nowarn("msg=deprecated")
private[io] class TcpIncomingConnection(
    _tcp: TcpExt,
    _channel: SocketChannel,
    registry: ChannelRegistry,
    bindHandler: ActorRef,
    options: immutable.Traversable[SocketOption],
    readThrottling: Boolean)
    extends TcpConnection(_tcp, _channel, readThrottling) {

  signDeathPact(bindHandler)

  registry.register(channel, initialOps = 0)

  def receive = {
    case registration: ChannelRegistration => completeConnect(registration, bindHandler, options)
  }
}
