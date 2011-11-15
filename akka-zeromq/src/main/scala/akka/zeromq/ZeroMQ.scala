/**
 * Copyright (C) 2009-2011 Typesafe Inc. <http://www.typesafe.com>
 */
package akka.zeromq

import akka.actor.{Actor, ActorRef}
import akka.dispatch.{Dispatchers, MessageDispatcher}
import akka.util.Duration
import akka.util.duration._
import akka.zeromq.SocketType._
import org.zeromq.{ZMQ => JZMQ}

case class SocketParameters(
  context: Context, 
  socketType: SocketType, 
  listener: Option[ActorRef] = None, 
  deserializer: Deserializer = new ZMQMessageDeserializer,
  pollTimeoutDuration: Duration = 100 millis
)

case class ZeroMQVersion(major: Int, minor: Int, patch: Int) {
  override def toString = "%d.%d.%d".format(major, minor, patch)
}

object ZeroMQ {
  def version = {
    ZeroMQVersion(JZMQ.getMajorVersion, JZMQ.getMinorVersion, JZMQ.getPatchVersion)  
  }
  def newContext(numIoThreads: Int = 1) = {
    verifyZeroMQVersion
    new Context(numIoThreads)
  }
  def newSocket(params: SocketParameters, supervisor: Option[ActorRef] = None, dispatcher: MessageDispatcher = Dispatchers.defaultGlobalDispatcher) = {
    verifyZeroMQVersion
    val socket = Actor.actorOf(new ConcurrentSocketActor(params, dispatcher))
    supervisor.foreach(_.link(socket))
    socket.start
  }
  private def verifyZeroMQVersion = {
    if (JZMQ.getFullVersion < JZMQ.makeVersion(2, 1, 0))
      throw new RuntimeException("Unsupported ZeroMQ version: %s".format(JZMQ.getVersionString))
  }
}
