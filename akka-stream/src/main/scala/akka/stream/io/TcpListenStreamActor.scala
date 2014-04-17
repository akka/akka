/**
 * Copyright (C) 2009-2014 Typesafe Inc. <http://www.typesafe.com>
 */
package akka.stream.io

import scala.util.control.NoStackTrace
import akka.actor._
import akka.stream.impl._
import akka.io.{ IO, Tcp }
import akka.io.Tcp._
import akka.util.ByteString
import org.reactivestreams.api.{ Consumer, Producer }
import org.reactivestreams.spi.Publisher
import akka.stream.MaterializerSettings

/**
 * INTERNAL API
 */
private[akka] object TcpListenStreamActor {
  class TcpListenStreamException(msg: String) extends RuntimeException(msg) with NoStackTrace

  def props(bindCmd: Tcp.Bind, requester: ActorRef, settings: MaterializerSettings): Props =
    Props(new TcpListenStreamActor(bindCmd, requester, settings))

  case class ConnectionProducer(getPublisher: Publisher[StreamTcp.IncomingTcpConnection])
    extends Producer[StreamTcp.IncomingTcpConnection] {

    def produceTo(consumer: Consumer[StreamTcp.IncomingTcpConnection]): Unit =
      getPublisher.subscribe(consumer.getSubscriber)
  }
}

/**
 * INTERNAL API
 */
private[akka] class TcpListenStreamActor(bindCmd: Tcp.Bind, requester: ActorRef, val settings: MaterializerSettings) extends Actor
  with PrimaryOutputs with Pump {
  import TcpListenStreamActor._
  import context.system

  var listener: ActorRef = _

  override def receive: Actor.Receive = waitingExposedPublisher
  override def primaryOutputsReady(): Unit = {
    IO(Tcp) ! bindCmd.copy(handler = self)
    context.become(waitBound)
  }

  val waitBound: Receive = {
    case Bound(localAddress) ⇒
      listener = sender()
      setTransferState(NeedsInputAndDemand)
      incomingConnections.prefetch()
      requester ! StreamTcp.TcpServerBinding(
        localAddress,
        ConnectionProducer(exposedPublisher.asInstanceOf[Publisher[StreamTcp.IncomingTcpConnection]]))
      context.become(running)
    case f: CommandFailed ⇒
      val ex = new TcpListenStreamException("Bind failed")
      requester ! Status.Failure(ex)
      fail(ex)
  }

  val running: Receive = downstreamManagement orElse {
    case c: Connected ⇒
      incomingConnections.enqueueInputElement((c, sender()))
      pump()
    case f: CommandFailed ⇒
      fail(new TcpListenStreamException(s"Command [${f.cmd}] failed"))
  }

  override def pumpOutputs(): Unit = pump()

  override def primaryOutputsFinished(completed: Boolean): Unit = shutdown()

  lazy val NeedsInputAndDemand = primaryOutputs.NeedsDemand && incomingConnections.NeedsInput

  override protected def transfer(): TransferState = {
    val (connected, connection) = incomingConnections.dequeueInputElement().asInstanceOf[(Connected, ActorRef)]
    val tcpStreamActor = context.actorOf(TcpStreamActor.inboundProps(connection, settings))
    val processor = new ActorProcessor[ByteString, ByteString](tcpStreamActor)
    primaryOutputs.enqueueOutputElement(StreamTcp.IncomingTcpConnection(connected.remoteAddress, processor, processor))
    NeedsInputAndDemand
  }

  override protected def pumpFinished(): Unit = incomingConnections.cancel()
  override protected def pumpFailed(e: Throwable): Unit = fail(e)
  override protected def pumpContext: ActorRefFactory = context

  val incomingConnections = new DefaultInputTransferStates {
    private var closed: Boolean = false
    private var pendingConnection: (Connected, ActorRef) = null

    override def inputsAvailable: Boolean = pendingConnection ne null
    override def inputsDepleted: Boolean = closed && !inputsAvailable
    override def prefetch(): Unit = listener ! ResumeAccepting(1)
    override def isClosed: Boolean = closed
    override def complete(): Unit = {
      if (!closed && listener != null) listener ! Unbind
      closed = true
    }
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
    override def enqueueInputElement(elem: Any): Unit = pendingConnection = elem.asInstanceOf[(Connected, ActorRef)]

  }

  def fail(e: Throwable): Unit = {
    incomingConnections.cancel()
    primaryOutputs.cancel(e)
    exposedPublisher.shutdown(Some(e))
  }

  def shutdown(): Unit = {
    incomingConnections.complete()
    primaryOutputs.complete()
    exposedPublisher.shutdown(None)
  }
}
