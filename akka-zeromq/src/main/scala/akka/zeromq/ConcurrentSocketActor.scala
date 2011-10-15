/**
 * Copyright (C) 2009-2011 Typesafe Inc. <http://www.typesafe.com>
 */
package akka.zeromq

import akka.actor.{Actor, ActorRef}
import akka.dispatch.MessageDispatcher
import akka.util.Duration
import akka.zeromq.SocketType._
import java.util.concurrent.atomic.AtomicReference
import org.zeromq.ZMQ.{Socket, Poller}
import org.zeromq.{ZMQ => JZMQ}
import scala.annotation.tailrec
import scala.collection.mutable.MutableList

private[zeromq] class ConcurrentSocketActor(params: SocketParameters, dispatcher: MessageDispatcher) extends Actor {
  private val noBytes = Array[Byte]()
  private val requests = new AtomicReference(Vector.empty[Request])
  private val socket: Socket = params.context.socket(params.socketType)
  private val poller: Poller = params.context.poller
  private var socketClosed: Boolean = false
  self.dispatcher = dispatcher
  poller.register(socket, Poller.POLLIN)
  private val selectTask = { () =>
    def connect(endpoint: String) {
      socket.connect(endpoint)
      params.listener.foreach { listener =>
        if (!listener.isShutdown)
          listener ! Connected
      }
    }
    def bind(endpoint: String) {
      socket.bind(endpoint)
    }
    def sendFrames(frames: Seq[Frame]) = for (i <- 0 until frames.length) {
      val flags = if (i < frames.length - 1) JZMQ.SNDMORE else 0
      sendBytes(frames(i).payload, flags)
    }
    def sendBytes(bytes: Seq[Byte], flags: Int) = {
      socket.send(bytes.toArray, flags)
    }
    def closeSocket = if (!socketClosed) {
      socketClosed = true
      socket.close
      params.listener.foreach { listener =>
        if (!listener.isShutdown)
          listener ! Closed
      }
    }
    if (!socketClosed) {
      if (poller.poll(params.pollTimeoutDuration.toMillis) > 0) {
        if (poller.pollin(0)) {
          receiveFrames match {
            case frames if (frames.length > 0) => params.listener.foreach { listener => 
              if (!listener.isShutdown)
                listener ! params.deserializer(frames)
            }
          }
        }
      }
      requests.getAndSet(Vector.empty).foreach {
        case Connect(endpoint) => connect(endpoint)
        case Bind(endpoint) => bind(endpoint)
        case Close => closeSocket
        case Send(frames) => sendFrames(frames)
        case Subscribe(topic) => socket.subscribe(topic.toArray)
        case Unsubscribe(topic) => socket.unsubscribe(topic.toArray)
      }
      if (!socketClosed) 
        select()
    }
  }
  override def preStart {
    select
  }
  override def postStop = if (!socketClosed) {
    addRequest(Close)
  }
  override def receive: Receive = {
    case ZMQMessage(frames) => addRequest(Send(frames))
    case request: Request => addRequest(request)
  }
  private def select() {
    self.dispatcher.dispatchTask(selectTask)
  }
  private def receiveFrames: Seq[Frame] = receiveBytes() match {
    case `noBytes` => Vector.empty
    case someBytes => {
      var frames = Vector(Frame(someBytes))
      while (socket.hasReceiveMore) receiveBytes() match {
        case `noBytes` =>
        case someBytes => frames :+= Frame(someBytes)
      }
      frames
    }
  }
  @inline private final def receiveBytes(): Array[Byte] = socket.recv(0) match {
    case null => noBytes
    case bytes: Array[Byte] if bytes.length > 0 => bytes
    case _ => noBytes
  }
  @tailrec private def addRequest(request: Request) {
    val oldRequests = requests.get
    val newRequests = oldRequests :+ request 
    if (!requests.compareAndSet(oldRequests, newRequests))
      addRequest(request)
  }
}
