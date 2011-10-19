/**
 * Copyright (C) 2009-2011 Typesafe Inc. <http://www.typesafe.com>
 */
package akka.zeromq

import akka.actor.{Actor, ActorRef}
import akka.dispatch.{Dispatchers, MessageDispatcher}
import akka.zeromq.SocketType._
import akka.util.Duration
import akka.util.duration._

case class SocketParameters(
  context: Context, 
  socketType: SocketType, 
  listener: Option[ActorRef] = None, 
  deserializer: Deserializer = new ZMQMessageDeserializer,
  pollTimeoutDuration: Duration = 100 millis
)

object ZeroMQ {
  def newContext = {
    new Context(1)
  }
  def newSocket(params: SocketParameters, supervisor: Option[ActorRef] = None, dispatcher: MessageDispatcher = Dispatchers.defaultGlobalDispatcher) = {
    val socket = Actor.actorOf(new ConcurrentSocketActor(params, dispatcher))
    supervisor.foreach(_.link(socket))
    socket.start
  }
}
