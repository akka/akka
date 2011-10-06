/**
 * Copyright (C) 2009-2011 Typesafe Inc. <http://www.typesafe.com>
 */
package akka.zeromq

import akka.actor.Actor
import org.zeromq.ZMQ.Socket
import org.zeromq.{ZMQ => ZeroMQ}

private[zeromq] abstract class AbstractSocketActor(socketType: Int, params: SocketParameters) extends Actor {
  protected var remoteSocket: Socket = _
  protected def bindOrConnectRemoteSocket = self.supervisor.foreach { sup =>
    remoteSocket = (sup ? SocketRequest(socketType)).get.asInstanceOf[Socket]
    params.direction match {
      case Connect => remoteSocket.connect(params.endpoint)
      case Bind    => remoteSocket.bind(params.endpoint)
    }
  }
  protected def receiveFrames(socket: Socket) = receiveBytes(socket) match {
    case Some(bytes) => { 
      var frames = List(Frame(bytes))
      while (socket.hasReceiveMore) {
        receiveBytes(socket) match { 
          case Some(bytes) => frames = Frame(bytes) :: frames
          case None        => Unit 
        }
      }
      frames.reverse.toArray
    }
    case _ => Array[Frame]()
  }
  private def receiveBytes(socket: Socket) = socket.recv(ZeroMQ.NOBLOCK) match { 
    case bytes: Array[Byte] if (bytes != null) => Some(bytes)
    case _                                     => None
  }
  protected def send(socket: Socket, frames: Array[Frame]) = for (i <- 0 until frames.length) {
    val flags = if (i < frames.length - 1) ZeroMQ.SNDMORE else 0
    socket.send(frames(i).payload, flags)
  }
  override def postStop {
    synchronized {
      if (remoteSocket != null)
        remoteSocket.close
    }
  }
}
