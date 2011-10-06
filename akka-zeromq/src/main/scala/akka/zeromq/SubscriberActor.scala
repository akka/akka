/**
 * Copyright (C) 2009-2011 Typesafe Inc. <http://www.typesafe.com>
 */
package akka.zeromq

import akka.actor.Actor._
import org.zeromq.{ZMQ => ZeroMQ}

private[zeromq] class SubscriberActor(params: SocketParameters) extends AbstractSocketActor(ZeroMQ.SUB, params) {
  override def postStop { }
  override def receive: Receive = {
    case Start => {
      bindOrConnectRemoteSocket
      receiveMessages
      self.reply(Ok)
    }
    case Subscribe(topic) => {
      remoteSocket.subscribe(topic)
      self.reply(Ok)
    }
    case Unsubscribe(topic) => {
      remoteSocket.unsubscribe(topic)
      self.reply(Ok)
    }
  }
  private def receiveMessages = spawn {
    while (self != null && self.isRunning) {
      receiveFrames(remoteSocket) match {
        case frames if (frames.length > 0) => params.listener.foreach {
          listener => listener ! params.deserializer(frames)
        }
        case _ => Unit
      }
    }
    if (remoteSocket != null)
      remoteSocket.close
  }
}
