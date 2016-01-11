/**
 * Copyright (C) 2009-2014 Typesafe Inc. <http://www.typesafe.com>
 */
package akka.stream.impl.io

import java.net.InetSocketAddress
import akka.io.{ IO, Tcp }
import scala.concurrent.Promise
import scala.util.control.NoStackTrace
import akka.actor._
import akka.util.ByteString
import akka.io.Tcp._
import akka.stream.ActorFlowMaterializerSettings
import akka.stream.StreamTcpException
import org.reactivestreams.Processor
import akka.stream.impl._
import akka.actor.ActorLogging

/**
 * INTERNAL API
 */
private[akka] object TcpStreamActor {
  case object WriteAck extends Tcp.Event

  def outboundProps(processorPromise: Promise[Processor[ByteString, ByteString]],
                    localAddressPromise: Promise[InetSocketAddress],
                    connectCmd: Connect,
                    materializerSettings: ActorFlowMaterializerSettings): Props =
    Props(new OutboundTcpStreamActor(processorPromise, localAddressPromise, connectCmd,
      materializerSettings)).withDispatcher(materializerSettings.dispatcher)

  def inboundProps(connection: ActorRef, settings: ActorFlowMaterializerSettings): Props =
    Props(new InboundTcpStreamActor(connection, settings)).withDispatcher(settings.dispatcher)
}

/**
 * INTERNAL API
 */
private[akka] abstract class TcpStreamActor(val settings: ActorFlowMaterializerSettings) extends Actor
  with ActorLogging {

  import TcpStreamActor._

  val primaryInputs: Inputs = new BatchingInputBuffer(settings.initialInputBufferSize, writePump) {
    override def inputOnError(e: Throwable): Unit = fail(e)
  }

  val primaryOutputs: Outputs = new SimpleOutputs(self, readPump)

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
        if (closed) connection ! ResumeReading
        else {
          pendingElement = data
          readPump.pump()
        }
      case ConfirmedClosed ⇒
        cancelWithoutTcpClose()
        readPump.pump()
      case PeerClosed ⇒
        cancelWithoutTcpClose()
        readPump.pump()
    }

    override def inputsAvailable: Boolean = pendingElement ne null
    override def inputsDepleted: Boolean = closed && !inputsAvailable
    override def isClosed: Boolean = closed

    override def cancel(): Unit = {
      if (!closed) {
        closed = true
        pendingElement = null
        if (connection ne null) {
          if (tcpOutputs.isClosed)
            connection ! Abort
          else
            connection ! ResumeReading
        }
      }
    }

    def cancelWithoutTcpClose(): Unit = {
      if (!closed) {
        closed = true
        pendingElement = null
      }
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

    def isClosed: Boolean = closed
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

    override def error(e: Throwable): Unit = {
      if (!closed && initialized) connection ! Abort
      closed = true
    }

    override def complete(): Unit = {
      if (!closed && initialized) {
        closed = true
        if (tcpInputs.isClosed)
          connection ! Close
        else
          connection ! ConfirmedClose
      }
    }

    override def cancel(): Unit = complete()

    override def enqueueOutputElement(elem: Any): Unit = {
      ReactiveStreamsCompliance.requireNonNullElement(elem)
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
      primaryInputs.cancel()
      tryShutdown()
    }
    override protected def pumpFailed(e: Throwable): Unit = fail(e)
  }

  object readPump extends Pump {

    def running = TransferPhase(tcpInputs.NeedsInput && primaryOutputs.NeedsDemand) { () ⇒
      primaryOutputs.enqueueOutputElement(tcpInputs.dequeueInputElement())
    }

    override protected def pumpFinished(): Unit = {
      tcpInputs.cancel()
      primaryOutputs.complete()
      tryShutdown()
    }
    override protected def pumpFailed(e: Throwable): Unit = fail(e)
  }

  final override def receive = new ExposedPublisherReceive(activeReceive, unhandled) {
    override def receiveExposedPublisher(ep: ExposedPublisher): Unit = {
      primaryOutputs.subreceive(ep)
      context become activeReceive
    }
  }

  def activeReceive =
    primaryInputs.subreceive orElse
      primaryOutputs.subreceive orElse
      tcpInputs.subreceive orElse
      tcpOutputs.subreceive orElse
      commonCloseHandling

  def commonCloseHandling: Receive = {
    case Closed ⇒
      tcpInputs.cancel()
      tcpOutputs.complete()
      writePump.pump()
      readPump.pump()
    case ErrorClosed(cause) ⇒ fail(new StreamTcpException(s"The connection closed with error $cause"))
    case CommandFailed(cmd) ⇒ fail(new StreamTcpException(s"Tcp command [$cmd] failed"))
    case Aborted            ⇒ fail(new StreamTcpException("The connection has been aborted"))
  }

  readPump.nextPhase(readPump.running)
  writePump.nextPhase(writePump.running)

  def fail(e: Throwable): Unit = {
    if (settings.debugLogging)
      log.debug("fail due to: {}", e.getMessage)
    tcpInputs.cancel()
    tcpOutputs.error(e)
    primaryInputs.cancel()
    primaryOutputs.error(e)
    tryShutdown()
  }

  def tryShutdown(): Unit =
    if (primaryInputs.isClosed && tcpInputs.isClosed && tcpOutputs.isClosed)
      context.stop(self)

  override def postStop(): Unit = {
    // Close if it has not yet been done
    tcpInputs.cancel()
    tcpOutputs.complete()
    primaryInputs.cancel()
    primaryOutputs.complete()
    super.postStop() // Remember, we have a Stash
  }
}

/**
 * INTERNAL API
 */
private[akka] class InboundTcpStreamActor(
  val connection: ActorRef, _settings: ActorFlowMaterializerSettings)
  extends TcpStreamActor(_settings) {

  connection ! Register(self, keepOpenOnPeerClosed = true, useResumeWriting = false)
  tcpInputs.setConnection(connection)
  tcpOutputs.setConnection(connection)
}

/**
 * INTERNAL API
 */
private[akka] class OutboundTcpStreamActor(processorPromise: Promise[Processor[ByteString, ByteString]],
                                           localAddressPromise: Promise[InetSocketAddress],
                                           val connectCmd: Connect, _settings: ActorFlowMaterializerSettings)
  extends TcpStreamActor(_settings) {
  import TcpStreamActor._
  import context.system

  val initSteps = new SubReceive(waitingExposedProcessor)

  override def activeReceive = initSteps orElse super.activeReceive

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
      localAddressPromise.success(localAddress)
      processorPromise.success(exposedProcessor)
      initSteps.become(Actor.emptyBehavior)

    case f: CommandFailed ⇒
      val ex = new StreamTcpException("Connection failed.")
      localAddressPromise.failure(ex)
      processorPromise.failure(ex)
      fail(ex)
  }
}
