/**
 * Copyright (C) 2009-2017 Lightbend Inc. <http://www.lightbend.com>
 */
package akka.stream.impl.io

import java.net.InetSocketAddress
import java.util.concurrent.TimeoutException
import java.util.concurrent.atomic.{ AtomicBoolean, AtomicLong }

import akka.NotUsed
import akka.actor.{ ActorRef, Terminated }
import akka.dispatch.ExecutionContexts
import akka.io.Inet.SocketOption
import akka.io.Tcp
import akka.io.Tcp._
import akka.stream._
import akka.stream.impl.ReactiveStreamsCompliance
import akka.stream.impl.fusing.GraphStages.detacher
import akka.stream.scaladsl.Tcp.{ OutgoingConnection, ServerBinding }
import akka.stream.scaladsl.{ BidiFlow, Flow, TcpIdleTimeoutException, Tcp ⇒ StreamTcp }
import akka.stream.stage._
import akka.util.ByteString

import scala.collection.immutable
import scala.concurrent.duration.{ Duration, FiniteDuration }
import scala.concurrent.{ Future, Promise }
import scala.util.Try

/**
 * INTERNAL API
 */
private[stream] class ConnectionSourceStage(
  val tcpManager:          ActorRef,
  val endpoint:            InetSocketAddress,
  val backlog:             Int,
  val options:             immutable.Traversable[SocketOption],
  val halfClose:           Boolean,
  val idleTimeout:         Duration,
  val bindShutdownTimeout: FiniteDuration)
  extends GraphStageWithMaterializedValue[SourceShape[StreamTcp.IncomingConnection], Future[StreamTcp.ServerBinding]] {
  import ConnectionSourceStage._

  val out: Outlet[StreamTcp.IncomingConnection] = Outlet("IncomingConnections.out")
  override def initialAttributes = Attributes.name("ConnectionSource")
  val shape: SourceShape[StreamTcp.IncomingConnection] = SourceShape(out)

  // TODO: Timeout on bind
  override def createLogicAndMaterializedValue(inheritedAttributes: Attributes): (GraphStageLogic, Future[ServerBinding]) = {
    val bindingPromise = Promise[ServerBinding]

    val logic = new TimerGraphStageLogic(shape) {
      implicit def self: ActorRef = stageActor.ref

      val connectionFlowsAwaitingInitialization = new AtomicLong()
      var listener: ActorRef = _
      var unbindPromise = Promise[Unit]()

      override def preStart(): Unit = {
        getStageActor(receive)
        tcpManager ! Tcp.Bind(self, endpoint, backlog, options, pullMode = true)
      }

      private def receive(evt: (ActorRef, Any)): Unit = {
        val sender = evt._1
        val msg = evt._2
        msg match {
          case Bound(localAddress) ⇒
            listener = sender
            stageActor.watch(listener)
            if (isAvailable(out)) listener ! ResumeAccepting(1)
            val thisStage = self
            bindingPromise.success(ServerBinding(localAddress)(() ⇒ {
              // Beware, sender must be explicit since stageActor.ref will be invalid to access after the stage
              // stopped.
              thisStage.tell(Unbind, thisStage)
              unbindPromise.future
            }))
          case f: CommandFailed ⇒
            val ex = BindFailedException
            bindingPromise.failure(ex)
            unbindPromise.success(() ⇒ Future.successful(()))
            failStage(ex)
          case c: Connected ⇒
            push(out, connectionFor(c, sender))
          case Unbind ⇒
            if (!isClosed(out) && (listener ne null)) tryUnbind()
          case Unbound ⇒ // If we're unbound then just shut down
            if (connectionFlowsAwaitingInitialization.get() == 0) completeStage()
            else scheduleOnce(BindShutdownTimer, bindShutdownTimeout)
          case Terminated(ref) if ref == listener ⇒
            failStage(new IllegalStateException("IO Listener actor terminated unexpectedly"))
        }
      }

      setHandler(out, new OutHandler {
        override def onPull(): Unit = {
          // Ignore if still binding
          if (listener ne null) listener ! ResumeAccepting(1)
        }

        override def onDownstreamFinish(): Unit = tryUnbind()
      })

      private def connectionFor(connected: Connected, connection: ActorRef): StreamTcp.IncomingConnection = {
        connectionFlowsAwaitingInitialization.incrementAndGet()

        val tcpFlow =
          Flow.fromGraph(new IncomingConnectionStage(connection, connected.remoteAddress, halfClose))
            .via(detacher[ByteString]) // must read ahead for proper completions
            .mapMaterializedValue { m ⇒
              connectionFlowsAwaitingInitialization.decrementAndGet()
              m
            }

        // FIXME: Previous code was wrong, must add new tests
        val handler = idleTimeout match {
          case d: FiniteDuration ⇒ tcpFlow.join(TcpIdleTimeout(d, Some(connected.remoteAddress)))
          case _                 ⇒ tcpFlow
        }

        StreamTcp.IncomingConnection(
          connected.localAddress,
          connected.remoteAddress,
          handler)
      }

      private def tryUnbind(): Unit = {
        if (listener ne null) {
          stageActor.unwatch(listener)
          setKeepGoing(true)
          listener ! Unbind
        }
      }

      override def onTimer(timerKey: Any): Unit = timerKey match {
        case BindShutdownTimer ⇒
          completeStage() // TODO need to manually shut down instead right?
      }

      override def postStop(): Unit = {
        unbindPromise.trySuccess(())
        bindingPromise.tryFailure(new NoSuchElementException("Binding was unbound before it was completely finished"))
      }
    }

    (logic, bindingPromise.future)
  }

}

private[stream] object ConnectionSourceStage {
  val BindTimer = "BindTimer"
  val BindShutdownTimer = "BindTimer"
}

/**
 * INTERNAL API
 */
private[stream] object TcpConnectionStage {
  case object WriteAck extends Tcp.Event

  trait TcpRole {
    def halfClose: Boolean
  }
  case class Outbound(
    manager:             ActorRef,
    connectCmd:          Connect,
    localAddressPromise: Promise[InetSocketAddress],
    halfClose:           Boolean) extends TcpRole
  case class Inbound(connection: ActorRef, halfClose: Boolean) extends TcpRole

  /*
   * This is a *non-detached* design, i.e. this does not prefetch itself any of the inputs. It relies on downstream
   * stages to provide the necessary prefetch on `bytesOut` and the framework to do the proper prefetch in the buffer
   * backing `bytesIn`. If prefetch on `bytesOut` is required (i.e. user stages cannot be trusted) then it is better
   * to attach an extra, fused buffer to the end of this flow. Keeping this stage non-detached makes it much simpler and
   * easier to maintain and understand.
   */
  class TcpStreamLogic(val shape: FlowShape[ByteString, ByteString], val role: TcpRole, remoteAddress: InetSocketAddress) extends GraphStageLogic(shape) {
    implicit def self: ActorRef = stageActor.ref

    private def bytesIn = shape.in
    private def bytesOut = shape.out
    private var connection: ActorRef = _

    // No reading until role have been decided
    setHandler(bytesOut, new OutHandler {
      override def onPull(): Unit = ()
    })

    override def preStart(): Unit = {
      setKeepGoing(true)
      role match {
        case Inbound(conn, _) ⇒
          setHandler(bytesOut, readHandler)
          connection = conn
          getStageActor(connected).watch(connection)
          connection ! Register(self, keepOpenOnPeerClosed = true, useResumeWriting = false)
          pull(bytesIn)
        case ob @ Outbound(manager, cmd, _, _) ⇒
          getStageActor(connecting(ob)).watch(manager)
          manager ! cmd
      }
    }

    private def connecting(ob: Outbound)(evt: (ActorRef, Any)): Unit = {
      val sender = evt._1
      val msg = evt._2
      msg match {
        case Terminated(_)      ⇒ failStage(new StreamTcpException("The IO manager actor (TCP) has terminated. Stopping now."))
        case CommandFailed(cmd) ⇒ failStage(new StreamTcpException(s"Tcp command [$cmd] failed"))
        case c: Connected ⇒
          role.asInstanceOf[Outbound].localAddressPromise.success(c.localAddress)
          connection = sender
          setHandler(bytesOut, readHandler)
          stageActor.unwatch(ob.manager)
          stageActor.become(connected)
          stageActor.watch(connection)
          connection ! Register(self, keepOpenOnPeerClosed = true, useResumeWriting = false)
          if (isAvailable(bytesOut)) connection ! ResumeReading
          pull(bytesIn)
      }
    }

    private def connected(evt: (ActorRef, Any)): Unit = {
      val sender = evt._1
      val msg = evt._2
      msg match {
        case Terminated(_)      ⇒ failStage(new StreamTcpException("The connection actor has terminated. Stopping now."))
        case CommandFailed(cmd) ⇒ failStage(new StreamTcpException(s"Tcp command [$cmd] failed"))
        case ErrorClosed(cause) ⇒ failStage(new StreamTcpException(s"The connection closed with error: $cause"))
        case Aborted            ⇒ failStage(new StreamTcpException("The connection has been aborted"))
        case Closed             ⇒ completeStage()
        case ConfirmedClosed    ⇒ completeStage()
        case PeerClosed         ⇒ complete(bytesOut)

        case Received(data) ⇒
          // Keep on reading even when closed. There is no "close-read-side" in TCP
          if (isClosed(bytesOut)) connection ! ResumeReading
          else push(bytesOut, data)

        case WriteAck ⇒ if (!isClosed(bytesIn)) pull(bytesIn)
      }
    }

    val readHandler = new OutHandler {
      override def onPull(): Unit = {
        connection ! ResumeReading
      }

      override def onDownstreamFinish(): Unit = {
        if (!isClosed(bytesIn)) connection ! ResumeReading
        else {
          connection ! Abort
          completeStage()
        }
      }
    }

    setHandler(bytesIn, new InHandler {
      override def onPush(): Unit = {
        val elem = grab(bytesIn)
        ReactiveStreamsCompliance.requireNonNullElement(elem)
        connection ! Write(elem.asInstanceOf[ByteString], WriteAck)
      }

      override def onUpstreamFinish(): Unit = {
        // Reading has stopped before, either because of cancel, or PeerClosed, so just Close now
        // (or half-close is turned off)
        if (isClosed(bytesOut) || !role.halfClose) connection ! Close
        // We still read, so we only close the write side
        else if (connection != null) connection ! ConfirmedClose
        else completeStage()
      }

      override def onUpstreamFailure(ex: Throwable): Unit = {
        if (connection != null) {
          if (interpreter.log.isDebugEnabled) {
            val msg = "Aborting tcp connection to {} because of upstream failure: {}"

            if (ex.getStackTrace.isEmpty) interpreter.log.debug(msg, remoteAddress, ex.getMessage)
            else interpreter.log.debug(msg + "\n{}", remoteAddress, ex.getMessage, ex.getStackTrace.mkString("\n"))
          }
          connection ! Abort
        } else failStage(ex)
      }
    })

    override def postStop(): Unit = role match {
      case Outbound(_, _, localAddressPromise, _) ⇒
        // Fail if has not been completed with an address earlier
        localAddressPromise.tryFailure(new StreamTcpException("Connection failed."))
      case _ ⇒ // do nothing...
    }
  }
}

/**
 * INTERNAL API
 */
class IncomingConnectionStage(connection: ActorRef, remoteAddress: InetSocketAddress, halfClose: Boolean)
  extends GraphStage[FlowShape[ByteString, ByteString]] {
  import TcpConnectionStage._

  private val hasBeenCreated = new AtomicBoolean(false)

  val bytesIn: Inlet[ByteString] = Inlet("IncomingTCP.in")
  val bytesOut: Outlet[ByteString] = Outlet("IncomingTCP.out")
  override def initialAttributes = Attributes.name("IncomingConnection")
  val shape: FlowShape[ByteString, ByteString] = FlowShape(bytesIn, bytesOut)

  override def createLogic(inheritedAttributes: Attributes): GraphStageLogic = {
    if (hasBeenCreated.get) throw new IllegalStateException("Cannot materialize an incoming connection Flow twice.")
    hasBeenCreated.set(true)

    new TcpStreamLogic(shape, Inbound(connection, halfClose), remoteAddress)
  }

  override def toString = s"TCP-from($remoteAddress)"
}

/**
 * INTERNAL API
 */
private[stream] class OutgoingConnectionStage(
  manager:        ActorRef,
  remoteAddress:  InetSocketAddress,
  localAddress:   Option[InetSocketAddress]           = None,
  options:        immutable.Traversable[SocketOption] = Nil,
  halfClose:      Boolean                             = true,
  connectTimeout: Duration                            = Duration.Inf)

  extends GraphStageWithMaterializedValue[FlowShape[ByteString, ByteString], Future[StreamTcp.OutgoingConnection]] {
  import TcpConnectionStage._

  val bytesIn: Inlet[ByteString] = Inlet("IncomingTCP.in")
  val bytesOut: Outlet[ByteString] = Outlet("IncomingTCP.out")
  override def initialAttributes = Attributes.name("OutgoingConnection")
  val shape: FlowShape[ByteString, ByteString] = FlowShape(bytesIn, bytesOut)

  override def createLogicAndMaterializedValue(inheritedAttributes: Attributes): (GraphStageLogic, Future[StreamTcp.OutgoingConnection]) = {
    // FIXME: A method like this would make soo much sense on Duration (i.e. toOption)
    val connTimeout = connectTimeout match {
      case x: FiniteDuration ⇒ Some(x)
      case _                 ⇒ None
    }

    val localAddressPromise = Promise[InetSocketAddress]
    val logic = new TcpStreamLogic(shape, Outbound(
      manager,
      Connect(remoteAddress, localAddress, options, connTimeout, pullMode = true),
      localAddressPromise,
      halfClose),
      remoteAddress)

    (logic, localAddressPromise.future.map(OutgoingConnection(remoteAddress, _))(ExecutionContexts.sameThreadExecutionContext))
  }

  override def toString = s"TCP-to($remoteAddress)"
}

/** INTERNAL API */
private[akka] object TcpIdleTimeout {
  def apply(idleTimeout: FiniteDuration, remoteAddress: Option[InetSocketAddress]): BidiFlow[ByteString, ByteString, ByteString, ByteString, NotUsed] = {
    val connectionToString = remoteAddress match {
      case Some(addr) ⇒ s" on connection to [$addr]"
      case _          ⇒ ""
    }

    val toNetTimeout: BidiFlow[ByteString, ByteString, ByteString, ByteString, NotUsed] =
      BidiFlow.fromFlows(
        Flow[ByteString].mapError {
          case t: TimeoutException ⇒
            new TcpIdleTimeoutException(s"TCP idle-timeout encountered$connectionToString, no bytes passed in the last $idleTimeout", idleTimeout)
        },
        Flow[ByteString]
      )
    val fromNetTimeout: BidiFlow[ByteString, ByteString, ByteString, ByteString, NotUsed] =
      toNetTimeout.reversed // now the bottom flow transforms the exception, the top one doesn't (since that one is "fromNet") 

    fromNetTimeout atop BidiFlow.bidirectionalIdleTimeout[ByteString, ByteString](idleTimeout) atop toNetTimeout
  }
}
