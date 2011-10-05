/**
 * Copyright (C) 2009-2011 Typesafe Inc. <http://www.typesafe.com>
 */
package akka.zeromq

import akka.actor.{Actor, ActorRef}
import akka.dispatch.MessageDispatcher
import akka.zeromq.SocketType._
import java.util.concurrent.atomic.AtomicReference
import org.zeromq.ZMQ.{Socket, Poller}
import org.zeromq.{ZMQ => JZMQ}
import scala.annotation.tailrec
import scala.collection.mutable.MutableList

private[zeromq] class ConcurrentSocketActor(
    context: Context, 
    socketType: SocketType, 
    listener: Option[ActorRef], 
    deserializer: Deserializer,
    dispatcher: MessageDispatcher) extends Actor {
  private val pollTimeoutMsec = 10
  private val requests = new AtomicReference(List.empty[Request])
  private val socket: Socket = context.socket(socketType)
  private val poller: Poller = context.poller
  private var socketClosed: Boolean = false
  self.dispatcher = dispatcher
  poller.register(socket, Poller.POLLIN)
  private val select = { () =>
    if (!socketClosed) {
      if (poller.poll(pollTimeoutMsec) > 0) {
        if (poller.pollin(0)) {
          receiveFrames match {
            case frames if (frames.length > 0) => listener.foreach { listener => 
              listener ! deserializer(frames)
            }
          }
        }
      }
      requests.getAndSet(Nil).foreach {
        case Connect(endpoint) => connect(endpoint)
        case Bind(endpoint) => bind(endpoint)
        case Close => closeSocket
        case Send(frames) => sendFrames(frames)
        case Subscribe(topic) => socket.subscribe(topic.toArray)
        case Unsubscribe(topic) => socket.unsubscribe(topic.toArray)
      }
      if (!socketClosed) 
        run()
    }
  }
  override def preStart {
    run
  }
  override def postStop = if (!socketClosed) {
    addRequest(Close)
  }
  override def receive: Receive = {
    case ZMQMessage(frames) => addRequest(Send(frames))
    case request: Request => addRequest(request)
  }
  private def run() {
    self.dispatcher.dispatchTask(select)
  }
  private def connect(endpoint: String) {
    socket.connect(endpoint)
    listener.foreach(_ ! Connected)
  }
  private def bind(endpoint: String) {
    socket.bind(endpoint)
  }
  private def sendFrames(frames: Seq[Frame]) = for (i <- 0 until frames.length) {
    val flags = if (i < frames.length - 1) JZMQ.SNDMORE else 0
    sendBytes(frames(i).payload, flags)
  }
  private def sendBytes(bytes: Seq[Byte], flags: Int) = {
    socket.send(bytes.toArray, flags)
  }
  private def receiveFrames: Seq[Frame] = receiveBytes match {
    case Some(bytes) => { 
      val frames = MutableList(Frame(bytes))
      while (socket.hasReceiveMore) {
        receiveBytes match { 
          case Some(bytes) => frames += Frame(bytes)
          case None => Unit 
        }
      }
      frames.toSeq
    }
    case _ => Array[Frame]()
  }
  private def receiveBytes: Option[Array[Byte]] = socket.recv(0) match {
    case null => None
    case bytes: Array[Byte] => Some(bytes)
    case _ => None                        
  }
  private def closeSocket = if (!socketClosed) {
    socketClosed = true
    socket.close
    listener.foreach(_ ! Closed)
  }
  @tailrec private def addRequest(request: Request) {
    val requests = this.requests.get
    if (!this.requests.compareAndSet(requests, request :: requests)) {
      addRequest(request)
    }
  }
}
