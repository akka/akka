/**
 * Copyright (C) 2009-2014 Typesafe Inc. <http://www.typesafe.com>
 */
package akka.stream.io

import akka.io.{ IO, Tcp }
import scala.util.control.NoStackTrace
import akka.actor.{ ActorRefFactory, Actor, Props, ActorRef, Status }
import akka.stream.impl._
import akka.util.ByteString
import akka.io.Tcp._
import akka.stream.MaterializerSettings
import org.reactivestreams.api.Processor

/**
 * INTERNAL API
 */
private[akka] object TcpStreamActor {
  case object WriteAck extends Tcp.Event
  class TcpStreamException(msg: String) extends RuntimeException(msg) with NoStackTrace

  def outboundProps(connectCmd: Connect, requester: ActorRef, settings: MaterializerSettings): Props =
    Props(new OutboundTcpStreamActor(connectCmd, requester, settings))
  def inboundProps(connection: ActorRef, settings: MaterializerSettings): Props =
    Props(new InboundTcpStreamActor(connection, settings))
}

/**
 * INTERNAL API
 */
private[akka] abstract class TcpStreamActor(val settings: MaterializerSettings) extends Actor
  with PrimaryInputs
  with PrimaryOutputs {

  import TcpStreamActor._
  def connection: ActorRef

  val tcpInputs = new DefaultInputTransferStates {
    private var closed: Boolean = false
    private var pendingElement: ByteString = null

    override def inputsAvailable: Boolean = pendingElement ne null
    override def inputsDepleted: Boolean = closed && !inputsAvailable
    override def prefetch(): Unit = connection ! ResumeReading
    override def isClosed: Boolean = closed
    override def complete(): Unit = closed = true
    override def cancel(): Unit = {
      closed = true
      pendingElement = null
    }
    override def dequeueInputElement(): Any = {
      val elem = pendingElement
      pendingElement = null
      connection ! ResumeReading
      elem
    }
    override def enqueueInputElement(elem: Any): Unit = pendingElement = elem.asInstanceOf[ByteString]

  }

  object tcpOutputs extends DefaultOutputTransferStates {
    private var closed: Boolean = false
    private var pendingDemand = true
    override def isClosed: Boolean = closed
    override def cancel(e: Throwable): Unit = {
      if (!closed) connection ! Abort
      closed = true
    }
    override def complete(): Unit = {
      if (!closed) connection ! ConfirmedClose
      closed = true
    }
    override def enqueueOutputElement(elem: Any): Unit = {
      connection ! Write(elem.asInstanceOf[ByteString], WriteAck)
      pendingDemand = false
    }
    def enqueueDemand(): Unit = pendingDemand = true

    override def demandAvailable: Boolean = pendingDemand
  }

  object writePump extends Pump {
    lazy val NeedsInputAndDemand = primaryInputs.NeedsInput && tcpOutputs.NeedsDemand
    override protected def transfer(): TransferState = {
      var batch = ByteString.empty
      while (primaryInputs.inputsAvailable) batch ++= primaryInputs.dequeueInputElement().asInstanceOf[ByteString]
      tcpOutputs.enqueueOutputElement(batch)
      NeedsInputAndDemand
    }
    override protected def pumpFinished(): Unit = tcpOutputs.complete()
    override protected def pumpFailed(e: Throwable): Unit = fail(e)
    override protected def pumpContext: ActorRefFactory = context
  }

  object readPump extends Pump {
    lazy val NeedsInputAndDemand = tcpInputs.NeedsInput && primaryOutputs.NeedsDemand
    override protected def transfer(): TransferState = {
      primaryOutputs.enqueueOutputElement(tcpInputs.dequeueInputElement())
      NeedsInputAndDemand
    }
    override protected def pumpFinished(): Unit = primaryOutputs.complete()
    override protected def pumpFailed(e: Throwable): Unit = fail(e)
    override protected def pumpContext: ActorRefFactory = context
  }

  override def pumpInputs(): Unit = writePump.pump()
  override def pumpOutputs(): Unit = readPump.pump()

  override def receive = waitingExposedPublisher

  override def primaryInputOnError(e: Throwable): Unit = fail(e)
  override def primaryInputOnComplete(): Unit = shutdown()
  override def primaryInputsReady(): Unit = {
    connection ! Register(self, keepOpenOnPeerClosed = true, useResumeWriting = false)
    readPump.setTransferState(readPump.NeedsInputAndDemand)
    writePump.setTransferState(writePump.NeedsInputAndDemand)
    tcpInputs.prefetch()
    context.become(running)
  }

  override def primaryOutputsReady(): Unit = context.become(downstreamManagement orElse waitingForUpstream)
  override def primaryOutputsFinished(completed: Boolean): Unit = shutdown()

  val running: Receive = upstreamManagement orElse downstreamManagement orElse {
    case WriteAck ⇒
      tcpOutputs.enqueueDemand()
      pumpInputs()
    case Received(data) ⇒
      tcpInputs.enqueueInputElement(data)
      pumpOutputs()
    case Closed ⇒
      tcpInputs.complete()
      tcpOutputs.complete()
      writePump.pump()
      readPump.pump()
    case ConfirmedClosed ⇒
      tcpInputs.complete()
      pumpOutputs()
    case PeerClosed ⇒
      tcpInputs.complete()
      pumpOutputs()
    case ErrorClosed(cause) ⇒ fail(new TcpStreamException(s"The connection closed with error $cause"))
    case CommandFailed(cmd) ⇒ fail(new TcpStreamException(s"Tcp command [$cmd] failed"))
    case Aborted            ⇒ fail(new TcpStreamException("The connection has been aborted"))
  }

  def fail(e: Throwable): Unit = {
    tcpInputs.cancel()
    tcpOutputs.cancel(e)
    if (primaryInputs ne null) primaryInputs.cancel()
    primaryOutputs.cancel(e)
    exposedPublisher.shutdown(Some(e))
  }

  def shutdown(): Unit = {
    if (tcpOutputs.isClosed && primaryOutputs.isClosed) {
      context.stop(self)
      exposedPublisher.shutdown(None)
    }
  }

}

/**
 * INTERNAL API
 */
private[akka] class InboundTcpStreamActor(
  val connection: ActorRef, _settings: MaterializerSettings)
  extends TcpStreamActor(_settings) {

}

/**
 * INTERNAL API
 */
private[akka] class OutboundTcpStreamActor(val connectCmd: Connect, val requester: ActorRef, _settings: MaterializerSettings)
  extends TcpStreamActor(_settings) {
  import TcpStreamActor._
  var connection: ActorRef = _
  import context.system

  override def primaryOutputsReady(): Unit = context.become(waitingExposedProcessor)

  val waitingExposedProcessor: Receive = {
    case StreamTcpManager.ExposedProcessor(processor) ⇒
      IO(Tcp) ! connectCmd
      context.become(waitConnection(processor))
    case _ ⇒ throw new IllegalStateException("The second message must be ExposedProcessor")
  }

  def waitConnection(exposedProcessor: Processor[ByteString, ByteString]): Receive = {
    case Connected(remoteAddress, localAddress) ⇒
      connection = sender()
      requester ! StreamTcp.OutgoingTcpConnection(remoteAddress, localAddress, exposedProcessor)
      context.become(downstreamManagement orElse waitingForUpstream)
    case f: CommandFailed ⇒
      val ex = new TcpStreamException("Connection failed.")
      requester ! Status.Failure(ex)
      fail(ex)
  }
}