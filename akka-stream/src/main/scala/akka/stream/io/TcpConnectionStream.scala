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
import org.reactivestreams.Processor

/**
 * INTERNAL API
 */
private[akka] object TcpStreamActor {
  case object WriteAck extends Tcp.Event
  class TcpStreamException(msg: String) extends RuntimeException(msg) with NoStackTrace

  def outboundProps(connectCmd: Connect, requester: ActorRef, settings: MaterializerSettings): Props =
    Props(new OutboundTcpStreamActor(connectCmd, requester, settings)).withDispatcher(settings.dispatcher)
  def inboundProps(connection: ActorRef, settings: MaterializerSettings): Props =
    Props(new InboundTcpStreamActor(connection, settings)).withDispatcher(settings.dispatcher)
}

/**
 * INTERNAL API
 */
private[akka] abstract class TcpStreamActor(val settings: MaterializerSettings) extends Actor {

  import TcpStreamActor._

  val primaryInputs: Inputs = new BatchingInputBuffer(settings.initialInputBufferSize, writePump) {
    override def inputOnError(e: Throwable): Unit = fail(e)
  }

  val primaryOutputs: Outputs =
    new FanoutOutputs(settings.maxFanOutBufferSize, settings.initialFanOutBufferSize, self, readPump) {
      override def afterShutdown(): Unit = {
        tcpInputs.cancel()
        TcpStreamActor.this.tryShutdown()
      }
    }

  object tcpInputs extends DefaultInputTransferStates {
    private var closed: Boolean = false
    private var pendingElement: ByteString = null
    private var connection: ActorRef = _

    val subreceive = new SubReceive(Actor.emptyBehavior)

    def setConnection(c: ActorRef): Unit = {
      connection = c
      // Prefetch
      c ! ResumeReading
      subreceive.become(handleRead)
      readPump.pump()
    }

    def handleRead: Receive = {
      case Received(data) ⇒
        pendingElement = data
        readPump.pump()
      case Closed ⇒
        closed = true
        tcpOutputs.complete()
        writePump.pump()
        readPump.pump()
      case ConfirmedClosed ⇒
        closed = true
        readPump.pump()
      case PeerClosed ⇒
        closed = true
        readPump.pump()
      case ErrorClosed(cause) ⇒ fail(new TcpStreamException(s"The connection closed with error $cause"))
      case CommandFailed(cmd) ⇒ fail(new TcpStreamException(s"Tcp command [$cmd] failed"))
      case Aborted            ⇒ fail(new TcpStreamException("The connection has been aborted"))
    }

    override def inputsAvailable: Boolean = pendingElement ne null
    override def inputsDepleted: Boolean = closed && !inputsAvailable
    override def isClosed: Boolean = closed

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

  }

  object tcpOutputs extends DefaultOutputTransferStates {
    private var closed: Boolean = false
    private var pendingDemand = true
    private var connection: ActorRef = _

    private def initialized: Boolean = connection ne null

    def setConnection(c: ActorRef): Unit = {
      connection = c
      writePump.pump()
      subreceive.become(handleWrite)
    }

    val subreceive = new SubReceive(Actor.emptyBehavior)

    def handleWrite: Receive = {
      case WriteAck ⇒
        pendingDemand = true
        writePump.pump()

    }

    override def isClosed: Boolean = closed
    override def cancel(e: Throwable): Unit = {
      if (!closed && initialized) connection ! Abort
      closed = true
    }
    override def complete(): Unit = {
      if (!closed && initialized) connection ! ConfirmedClose
      closed = true
    }
    override def enqueueOutputElement(elem: Any): Unit = {
      connection ! Write(elem.asInstanceOf[ByteString], WriteAck)
      pendingDemand = false
    }

    override def demandAvailable: Boolean = pendingDemand
  }

  object writePump extends Pump {

    def running = TransferPhase(primaryInputs.NeedsInput && tcpOutputs.NeedsDemand) { () ⇒
      var batch = ByteString.empty
      while (primaryInputs.inputsAvailable) batch ++= primaryInputs.dequeueInputElement().asInstanceOf[ByteString]
      tcpOutputs.enqueueOutputElement(batch)
    }

    override protected def pumpFinished(): Unit = {
      tcpOutputs.complete()
      tryShutdown()
    }
    override protected def pumpFailed(e: Throwable): Unit = fail(e)
    override protected def pumpContext: ActorRefFactory = context
  }

  object readPump extends Pump {

    def running = TransferPhase(tcpInputs.NeedsInput && primaryOutputs.NeedsDemand) { () ⇒
      primaryOutputs.enqueueOutputElement(tcpInputs.dequeueInputElement())
    }

    override protected def pumpFinished(): Unit = {
      primaryOutputs.complete()
      tryShutdown()
    }
    override protected def pumpFailed(e: Throwable): Unit = fail(e)
    override protected def pumpContext: ActorRefFactory = context
  }

  override def receive =
    primaryInputs.subreceive orElse primaryOutputs.subreceive orElse tcpInputs.subreceive orElse tcpOutputs.subreceive

  readPump.nextPhase(readPump.running)
  writePump.nextPhase(writePump.running)

  def fail(e: Throwable): Unit = {
    tcpInputs.cancel()
    tcpOutputs.cancel(e)
    primaryInputs.cancel()
    primaryOutputs.cancel(e)
  }

  def tryShutdown(): Unit = if (primaryInputs.isClosed && tcpInputs.isClosed && tcpOutputs.isClosed) context.stop(self)

}

/**
 * INTERNAL API
 */
private[akka] class InboundTcpStreamActor(
  val connection: ActorRef, _settings: MaterializerSettings)
  extends TcpStreamActor(_settings) {

  connection ! Register(self, keepOpenOnPeerClosed = true, useResumeWriting = false)
  tcpInputs.setConnection(connection)
  tcpOutputs.setConnection(connection)
}

/**
 * INTERNAL API
 */
private[akka] class OutboundTcpStreamActor(val connectCmd: Connect, val requester: ActorRef, _settings: MaterializerSettings)
  extends TcpStreamActor(_settings) {
  import TcpStreamActor._
  import context.system

  val initSteps = new SubReceive(waitingExposedProcessor)

  override def receive = initSteps orElse super.receive

  def waitingExposedProcessor: Receive = {
    case StreamTcpManager.ExposedProcessor(processor) ⇒
      IO(Tcp) ! connectCmd
      initSteps.become(waitConnection(processor))
  }

  def waitConnection(exposedProcessor: Processor[ByteString, ByteString]): Receive = {
    case Connected(remoteAddress, localAddress) ⇒
      val connection = sender()
      connection ! Register(self, keepOpenOnPeerClosed = true, useResumeWriting = false)
      tcpOutputs.setConnection(connection)
      tcpInputs.setConnection(connection)
      requester ! StreamTcp.OutgoingTcpConnection(remoteAddress, localAddress, exposedProcessor)
      initSteps.become(Actor.emptyBehavior)
    case f: CommandFailed ⇒
      val ex = new TcpStreamException("Connection failed.")
      requester ! Status.Failure(ex)
      fail(ex)
  }
}