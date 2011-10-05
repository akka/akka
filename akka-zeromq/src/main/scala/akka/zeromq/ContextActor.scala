/**
 * Copyright (C) 2009-2011 Typesafe Inc. <http://www.typesafe.com>
 */
package akka.zeromq

import akka.actor.Actor
import org.zeromq.ZMQ.Context
import org.zeromq.{ZMQ => ZeroMQ}

private[zeromq] class ContextActor extends Actor {
  private var context: Context = _
  override def receive: Receive = {
    case Start => {
      context = ZeroMQ.context(1)
      self.reply(Ok)
    }
    case SocketRequest(socketType) => {
      self.reply(context.socket(socketType))
    }
    case PollerRequest => {
      self.reply(context.poller)
    }
  }
}
