/**
 * Copyright (C) 2009-2014 Typesafe Inc. <http://www.typesafe.com>
 */
package akka.stream.impl.io

import java.net.InetSocketAddress
import akka.io.{ IO, Tcp }
import akka.stream.impl.io.StreamTcpManager.ExposedProcessor
import scala.concurrent.Promise
import akka.actor._
import akka.util.ByteString
import akka.io.Tcp._
import akka.stream.{ AbruptTerminationException, StreamSubscriptionTimeoutSettings, ActorMaterializerSettings, StreamTcpException }
import org.reactivestreams.{ Publisher, Processor }
import akka.stream.impl._

import scala.util.control.NoStackTrace

/**
 * INTERNAL API
 */
private[akka] object TcpStreamActor {
  case object WriteAck extends Tcp.Event

  def outboundProps(processorPromise: Promise[Processor[ByteString, ByteString]],
                    localAddressPromise: Promise[InetSocketAddress],
                    halfClose: Boolean,
                    connectCmd: Connect,
                    materializerSettings: ActorMaterializerSettings): Props =
    Props(new OutboundTcpStreamActor(processorPromise, localAddressPromise, halfClose, connectCmd,
      materializerSettings)).withDispatcher(materializerSettings.dispatcher).withDeploy(Deploy.local)

  def inboundProps(connection: ActorRef, halfClose: Boolean, settings: ActorMaterializerSettings): Props =
    Props(new InboundTcpStreamActor(connection, halfClose, settings)).withDispatcher(settings.dispatcher).withDeploy(Deploy.local)

  case object SubscriptionTimeout extends NoSerializationVerificationNeeded
}

/**
 * INTERNAL API
 */
private[akka] abstract class TcpStreamActor(val settings: ActorMaterializerSettings, halfClose: Boolean) extends Actor
  with ActorLogging {

  import TcpStreamActor._

  val primaryInputs: Inputs = new BatchingInputBuffer(settings.initialInputBufferSize, writePump) {
    override def inputOnError(e: Throwable): Unit = fail(e)
  }

  val primaryOutputs: SimpleOutputs = new SimpleOutputs(self, readPump)

  def fullClose: Boolean = !halfClose

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
        cancel()
        readPump.pump()
      case PeerClosed ⇒
        cancel()
        readPump.pump()
    }

    override def inputsAvailable: Boolean = pendingElement ne null
    override def inputsDepleted: Boolean = closed && !inputsAvailable
    override def isClosed: Boolean = closed

    override def cancel(): Unit = {
      if (!closed) {
        closed = true
        pendingElement = null
        if (!tcpOutputs.isFlushed && (connection ne null)) connection ! ResumeReading
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
    private var lastWriteAcked = true
    private var connection: ActorRef = _

    def isClosed: Boolean = closed
    // Full-close mode needs to wait for the last write Ack before sending Close to avoid doing a connection reset
    def isFlushed: Boolean = closed && (halfClose || lastWriteAcked)

    private def initialized: Boolean = connection ne null

    def setConnection(c: ActorRef): Unit = {
      connection = c
      writePump.pump()
      subreceive.become(handleWrite)
    }

    val subreceive = new SubReceive(Actor.emptyBehavior)

    def handleWrite: Receive = {
      case WriteAck ⇒
        lastWriteAcked = true
        if (fullClose && closed) {
          // Finish the closing after the last write has been flushed in full close mode.
          connection ! Close
          tryShutdown()
        }
        writePump.pump()

    }

    override def error(e: Throwable): Unit = {
      if (!closed && initialized) connection ! Abort
      closed = true
    }

    override def complete(): Unit = {
      if (!closed && initialized) {
        closed = true

        if (halfClose) {
          if (tcpInputs.isClosed) {
            // Reading has stopped, either because of cancel, or PeerClosed, just Close now
            connection ! Close
            tryShutdown()
          } else {
            // We still read, so we only close the write side
            connection ! ConfirmedClose
          }
        } else {
          if (lastWriteAcked) {
            // No pending writes, close now
            connection ! Close
            tryShutdown()
          }
          // Else wait for final Ack (see handleWrite)
        }
      }
    }

    override def cancel(): Unit = complete()

    override def enqueueOutputElement(elem: Any): Unit = {
      ReactiveStreamsCompliance.requireNonNullElement(elem)
      connection ! Write(elem.asInstanceOf[ByteString], WriteAck)
      lastWriteAcked = false
    }

    override def demandAvailable: Boolean = lastWriteAcked
  }

  object writePump extends Pump {

    def running = TransferPhase(primaryInputs.NeedsInput && tcpOutputs.NeedsDemand) { () ⇒
      var batch = ByteString.empty
      while (primaryInputs.inputsAvailable) batch ++= primaryInputs.dequeueInputElement().asInstanceOf[ByteString]
      tcpOutputs.enqueueOutputElement(batch)
    }

    override protected def pumpFinished(): Unit = {
      if (fullClose) {
        // In full close mode we shut down the read size immediately once the write side is finished
        tcpInputs.cancel()
        primaryOutputs.complete()
        readPump.pump()
      }
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
      import context.dispatcher
      primaryOutputs.subreceive(ep)
      subscriptionTimer = Some(
        context.system.scheduler.scheduleOnce(
          settings.subscriptionTimeoutSettings.timeout,
          self,
          SubscriptionTimeout))

      context become activeReceive
    }
  }

  def activeReceive =
    primaryInputs.subreceive orElse
      primaryOutputs.subreceive orElse
      tcpInputs.subreceive orElse
      tcpOutputs.subreceive orElse
      commonCloseHandling orElse
      handleSubscriptionTimeout

  def commonCloseHandling: Receive = {
    case Terminated(_) ⇒ fail(new StreamTcpException("The connection actor has terminated. Stopping now."))
    case Closed ⇒
      tcpInputs.cancel()
      tcpOutputs.complete()
      writePump.pump()
      readPump.pump()
    case ErrorClosed(cause) ⇒ fail(new StreamTcpException(s"The connection closed with error $cause"))
    case CommandFailed(cmd) ⇒ fail(new StreamTcpException(s"Tcp command [$cmd] failed"))
    case Aborted            ⇒ fail(new StreamTcpException("The connection has been aborted"))
  }

  def handleSubscriptionTimeout: Receive = {
    case SubscriptionTimeout ⇒
      val millis = settings.subscriptionTimeoutSettings.timeout.toMillis
      if (!primaryOutputs.isSubscribed) {
        fail(new SubscriptionTimeoutException(s"Publisher was not attached to upstream within deadline (${millis}) ms") with NoStackTrace)
        context.stop(self)
      }
  }

  readPump.nextPhase(readPump.running)
  writePump.nextPhase(writePump.running)

  var subscriptionTimer: Option[Cancellable] = None

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
    if (primaryInputs.isClosed && tcpInputs.isClosed && tcpOutputs.isFlushed)
      context.stop(self)

  override def postStop(): Unit = {
    // Close if it has not yet been done
    val abruptTermination = AbruptTerminationException(self)
    tcpInputs.cancel()
    tcpOutputs.error(abruptTermination)
    primaryInputs.cancel()
    primaryOutputs.error(abruptTermination)
    subscriptionTimer.foreach(_.cancel())
    super.postStop() // Remember, we have a Stash
  }
}

/**
 * INTERNAL API
 */
private[akka] class InboundTcpStreamActor(
  val connection: ActorRef, _halfClose: Boolean, _settings: ActorMaterializerSettings)
  extends TcpStreamActor(_settings, _halfClose) {
  context.watch(connection)

  connection ! Register(self, keepOpenOnPeerClosed = true, useResumeWriting = false)
  tcpInputs.setConnection(connection)
  tcpOutputs.setConnection(connection)
}

/**
 * INTERNAL API
 */
private[akka] class OutboundTcpStreamActor(processorPromise: Promise[Processor[ByteString, ByteString]],
                                           localAddressPromise: Promise[InetSocketAddress],
                                           _halfClose: Boolean,
                                           val connectCmd: Connect, _settings: ActorMaterializerSettings)
  extends TcpStreamActor(_settings, _halfClose) {
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
      context.watch(connection)
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

  override def fail(e: Throwable): Unit = {
    processorPromise.tryFailure(e)
    localAddressPromise.tryFailure(e)
    super.fail(e)
  }
}
