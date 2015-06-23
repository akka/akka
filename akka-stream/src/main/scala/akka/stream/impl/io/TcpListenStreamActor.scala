/**
 * Copyright (C) 2009-2014 Typesafe Inc. <http://www.typesafe.com>
 */
package akka.stream.impl.io

import java.net.InetSocketAddress
import scala.concurrent.{ Future, Promise }
import akka.actor._
import akka.io.{ IO, Tcp }
import akka.io.Tcp._
import akka.stream.{ Materializer, ActorMaterializerSettings }
import akka.stream.impl._
import akka.stream.scaladsl.Flow
import akka.stream.scaladsl.{ Tcp ⇒ StreamTcp }
import akka.util.ByteString
import org.reactivestreams.Subscriber
import akka.stream.ConnectionException
import akka.stream.BindFailedException

/**
 * INTERNAL API
 */
private[akka] object TcpListenStreamActor {
  def props(localAddressPromise: Promise[InetSocketAddress],
            unbindPromise: Promise[() ⇒ Future[Unit]],
            flowSubscriber: Subscriber[StreamTcp.IncomingConnection],
            halfClose: Boolean,
            bindCmd: Tcp.Bind, materializerSettings: ActorMaterializerSettings): Props = {
    Props(new TcpListenStreamActor(localAddressPromise, unbindPromise, flowSubscriber, halfClose, bindCmd, materializerSettings))
      .withDeploy(Deploy.local)
  }
}

/**
 * INTERNAL API
 */
private[akka] class TcpListenStreamActor(localAddressPromise: Promise[InetSocketAddress],
                                         unbindPromise: Promise[() ⇒ Future[Unit]],
                                         flowSubscriber: Subscriber[StreamTcp.IncomingConnection],
                                         halfClose: Boolean,
                                         bindCmd: Tcp.Bind, settings: ActorMaterializerSettings) extends Actor
  with Pump with ActorLogging {
  import ReactiveStreamsCompliance._
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

  private val unboundPromise = Promise[Unit]()
  private var finished = false

  override protected def pumpFinished(): Unit = {
    if (!finished) {
      finished = true
      incomingConnections.cancel()
      primaryOutputs.complete()
      // Stop only after all already accepted connections have been shut down
      if (context.children.isEmpty) context.stop(self)
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
        localAddressPromise.success(localAddress)
        unbindPromise.success(() ⇒ { target ! Unbind; unboundPromise.future })
        primaryOutputs.getExposedPublisher.subscribe(flowSubscriber.asInstanceOf[Subscriber[Any]])
        subreceive.become(running)
      case f: CommandFailed ⇒
        val ex = BindFailedException
        localAddressPromise.failure(ex)
        unbindPromise.success(() ⇒ Future.successful(()))
        try {
          tryOnSubscribe(flowSubscriber, CancelledSubscription)
          tryOnError(flowSubscriber, ex)
        } finally fail(ex)
    }

    def running: Receive = {
      case c: Connected ⇒
        pendingConnection = (c, sender())
        pump()
      case f: CommandFailed ⇒
        val ex = new ConnectionException(s"Command [${f.cmd}] failed")
        if (f.cmd.isInstanceOf[Unbind.type]) unboundPromise.tryFailure(BindFailedException)
        fail(ex)
      case Unbind ⇒
        if (!closed && listener != null) listener ! Unbind
        listener = null
        pump()
      case Unbound ⇒ // If we're unbound then just shut down
        cancel()
        unboundPromise.trySuccess(())
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
      pump()
    }
    override def dequeueInputElement(): Any = {
      val elem = pendingConnection
      pendingConnection = null
      listener ! ResumeAccepting(1)
      elem
    }
  }

  final override def receive = new ExposedPublisherReceive(activeReceive, unhandled) {
    override def receiveExposedPublisher(ep: ExposedPublisher): Unit = {
      primaryOutputs.subreceive(ep)
      context become activeReceive
    }
  }

  def activeReceive: Actor.Receive = primaryOutputs.subreceive orElse incomingConnections.subreceive orElse {
    case Terminated(_) ⇒
      // If the Source is cancelled, and this was our last child, stop ourselves
      if (incomingConnections.isClosed && context.children.isEmpty) context.stop(self)
  }

  def runningPhase = TransferPhase(primaryOutputs.NeedsDemand && incomingConnections.NeedsInput) { () ⇒
    val (connected: Connected, connection: ActorRef) = incomingConnections.dequeueInputElement()
    val tcpStreamActor = context.watch(context.actorOf(TcpStreamActor.inboundProps(connection, halfClose, settings)))
    val processor = ActorProcessor[ByteString, ByteString](tcpStreamActor)
    val conn = StreamTcp.IncomingConnection(
      connected.localAddress,
      connected.remoteAddress,
      Flow[ByteString].andThenMat(() ⇒ (processor, ())))
    primaryOutputs.enqueueOutputElement(conn)
  }

  override def postStop(): Unit = {
    unboundPromise.trySuccess(())
    primaryOutputs.complete()
    super.postStop()
  }

  def fail(e: Throwable): Unit = {
    if (settings.debugLogging)
      log.debug("fail due to: {}", e.getMessage)
    incomingConnections.cancel()
    primaryOutputs.error(e)
  }
}
