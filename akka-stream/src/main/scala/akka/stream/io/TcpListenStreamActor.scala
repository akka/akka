/**
 * Copyright (C) 2009-2014 Typesafe Inc. <http://www.typesafe.com>
 */
package akka.stream.io

import java.io.Closeable

import akka.actor._
import akka.io.Tcp._
import akka.io.{ IO, Tcp }
import akka.stream.MaterializerSettings
import akka.stream.impl._
import akka.util.ByteString
import org.reactivestreams.Publisher

import scala.util.control.NoStackTrace

/**
 * INTERNAL API
 */
private[akka] object TcpListenStreamActor {
  class TcpListenStreamException(msg: String) extends RuntimeException(msg) with NoStackTrace

  def props(bindCmd: Tcp.Bind, requester: ActorRef, settings: MaterializerSettings): Props = {
    Props(new TcpListenStreamActor(bindCmd, requester, settings))
  }

}

/**
 * INTERNAL API
 */
private[akka] class TcpListenStreamActor(bindCmd: Tcp.Bind, requester: ActorRef, settings: MaterializerSettings) extends Actor
  with Pump with Stash {
  import akka.stream.io.TcpListenStreamActor._
  import context.system

  object primaryOutputs extends SimpleOutputs(self, pump = this) {

    override def waitingExposedPublisher: Actor.Receive = {
      case ExposedPublisher(publisher) ⇒
        exposedPublisher = publisher
        IO(Tcp) ! bindCmd.copy(handler = self)
        subreceive.become(downstreamRunning)
      case other ⇒
        throw new IllegalStateException(s"The first message must be ExposedPublisher but was [$other]")
    }

    def getExposedPublisher = exposedPublisher
  }

  private var finished = false
  override protected def pumpFinished(): Unit = {
    if (!finished) {
      finished = true
      incomingConnections.cancel()
      primaryOutputs.complete()
      context.stop(self)
    }
  }

  override protected def pumpFailed(e: Throwable): Unit = fail(e)

  val incomingConnections: Inputs = new DefaultInputTransferStates {
    var listener: ActorRef = _
    private var closed: Boolean = false
    private var pendingConnection: (Connected, ActorRef) = null

    def waitBound: Receive = {
      case Bound(localAddress) ⇒
        listener = sender()
        nextPhase(runningPhase)
        listener ! ResumeAccepting(1)
        val target = self
        requester ! StreamTcp.TcpServerBinding(
          localAddress,
          primaryOutputs.getExposedPublisher.asInstanceOf[Publisher[StreamTcp.IncomingTcpConnection]],
          new Closeable {
            override def close() = target ! Unbind
          })
        subreceive.become(running)
      case f: CommandFailed ⇒
        val ex = new TcpListenStreamException("Bind failed")
        requester ! Status.Failure(ex)
        fail(ex)
    }

    def running: Receive = {
      case c: Connected ⇒
        pendingConnection = (c, sender())
        pump()
      case f: CommandFailed ⇒
        fail(new TcpListenStreamException(s"Command [${f.cmd}] failed"))
      case Unbind ⇒
        cancel()
        pump()
      case Unbound ⇒ // If we're unbound then just shut down
        closed = true
        pump()
    }

    override val subreceive = new SubReceive(waitBound)

    override def inputsAvailable: Boolean = pendingConnection ne null
    override def inputsDepleted: Boolean = closed && !inputsAvailable
    override def isClosed: Boolean = closed
    override def cancel(): Unit = {
      if (!closed && listener != null) listener ! Unbind
      closed = true
      pendingConnection = null
    }
    override def dequeueInputElement(): Any = {
      val elem = pendingConnection
      pendingConnection = null
      listener ! ResumeAccepting(1)
      elem
    }

  }

  final override def receive = {
    // FIXME using Stash mailbox is not the best for performance, we probably want a better solution to this
    case ep: ExposedPublisher ⇒
      primaryOutputs.subreceive(ep)
      context become activeReceive
      unstashAll()
    case _ ⇒ stash()
  }

  def activeReceive: Actor.Receive = primaryOutputs.subreceive orElse incomingConnections.subreceive

  def runningPhase = TransferPhase(primaryOutputs.NeedsDemand && incomingConnections.NeedsInput) { () ⇒
    val (connected: Connected, connection: ActorRef) = incomingConnections.dequeueInputElement()
    val tcpStreamActor = context.actorOf(TcpStreamActor.inboundProps(connection, settings))
    val processor = ActorProcessor[ByteString, ByteString](tcpStreamActor)
    primaryOutputs.enqueueOutputElement(StreamTcp.IncomingTcpConnection(connected.remoteAddress, processor, processor))
  }

  def fail(e: Throwable): Unit = {
    incomingConnections.cancel()
    primaryOutputs.cancel(e)
  }
}
