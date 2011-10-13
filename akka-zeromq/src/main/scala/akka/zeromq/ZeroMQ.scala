/**
 * Copyright (C) 2009-2011 Typesafe Inc. <http://www.typesafe.com>
 */
package akka.zeromq

import akka.actor.{Actor, ActorRef}
import akka.dispatch.{Dispatchers, MessageDispatcher}
import akka.zeromq.SocketType._
import akka.util.Duration
import akka.util.duration._

object ZeroMQ {
  def newContext = {
    new Context(1)
  }
  def newSocket(context: Context, 
      socketType: SocketType, 
      listener: Option[ActorRef] = None, 
      deserializer: Deserializer = new ZMQMessageDeserializer,
      dispatcher: MessageDispatcher = Dispatchers.defaultGlobalDispatcher,
      pollTimeoutDuration: Duration = 100 millis) = {
    Actor.actorOf(new ConcurrentSocketActor(context, socketType, listener, deserializer, dispatcher, pollTimeoutDuration)).start
  }
}
