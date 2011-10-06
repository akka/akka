/**
 * Copyright (C) 2009-2011 Typesafe Inc. <http://www.typesafe.com>
 */
package akka.zeromq

import org.zeromq.{ZMQ => ZeroMQ}
import com.google.protobuf.Message

private[zeromq] class PublisherActor(params: SocketParameters) extends AbstractSocketActor(ZeroMQ.PUB, params) {
  override def receive: Receive = {
    case Start => {
      bindOrConnectRemoteSocket
      self.reply(Ok)
    }
    case message: ZMQMessage => {
      send(remoteSocket, message.frames)
    }
    case message: Message => {
      remoteSocket.send(message.toByteArray, 0)
    }
  }
}
