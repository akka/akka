/**
 * Copyright (C) 2009-2014 Typesafe Inc. <http://www.typesafe.com>
 */
package akka.stream.scaladsl

import java.net.{ InetSocketAddress, URLEncoder }
import akka.stream.impl.StreamLayout.Module
import scala.collection.immutable
import scala.concurrent.{ Promise, ExecutionContext, Future }
import scala.concurrent.duration.Duration
import scala.util.{ Failure, Success }
import scala.util.control.NoStackTrace
import akka.actor.Actor
import akka.actor.ActorRef
import akka.actor.ActorSystem
import akka.actor.ExtendedActorSystem
import akka.actor.ExtensionId
import akka.actor.ExtensionIdProvider
import akka.actor.Props
import akka.io.Inet.SocketOption
import akka.io.Tcp
import akka.stream._
import akka.stream.impl._
import akka.stream.impl.ReactiveStreamsCompliance._
import akka.stream.scaladsl._
import akka.util.ByteString
import org.reactivestreams.{ Publisher, Processor, Subscriber, Subscription }
import akka.actor.actorRef2Scala
import akka.stream.impl.io.TcpStreamActor
import akka.stream.impl.io.TcpListenStreamActor
import akka.stream.impl.io.DelayedInitProcessor
import akka.stream.impl.io.StreamTcpManager

object StreamTcp extends ExtensionId[StreamTcp] with ExtensionIdProvider {

  /**
   * * Represents a succdessful TCP server binding.
   */
  case class ServerBinding(localAddress: InetSocketAddress)(private val unbindAction: () ⇒ Future[Unit]) {
    def unbind(): Future[Unit] = unbindAction()
  }

  /**
   * Represents an accepted incoming TCP connection.
   */
  case class IncomingConnection(
    localAddress: InetSocketAddress,
    remoteAddress: InetSocketAddress,
    flow: Flow[ByteString, ByteString, Unit]) {

    /**
     * Handles the connection using the given flow, which is materialized exactly once and the respective
     * materialized instance is returned.
     *
     * Convenience shortcut for: `flow.join(handler).run()`.
     */
    def handleWith[Mat](handler: Flow[ByteString, ByteString, Mat])(implicit materializer: FlowMaterializer): Mat =
      flow.joinMat(handler)(Keep.right).run()

  }

  /**
   * Represents a prospective outgoing TCP connection.
   */
  case class OutgoingConnection(remoteAddress: InetSocketAddress, localAddress: InetSocketAddress)

  def apply()(implicit system: ActorSystem): StreamTcp = super.apply(system)

  override def get(system: ActorSystem): StreamTcp = super.get(system)

  def lookup() = StreamTcp

  def createExtension(system: ExtendedActorSystem): StreamTcp = new StreamTcp(system)
}

class StreamTcp(system: ExtendedActorSystem) extends akka.actor.Extension {
  import StreamTcp._

  private val manager: ActorRef = system.systemActorOf(Props[StreamTcpManager]
    .withDispatcher(Tcp(system).Settings.ManagementDispatcher), name = "IO-TCP-STREAM")

  private class BindSource(
    val endpoint: InetSocketAddress,
    val backlog: Int,
    val options: immutable.Traversable[SocketOption],
    val idleTimeout: Duration = Duration.Inf,
    val attributes: OperationAttributes,
    _shape: SourceShape[IncomingConnection]) extends SourceModule[IncomingConnection, Future[ServerBinding]](_shape) {

    override def create(context: MaterializationContext): (Publisher[IncomingConnection], Future[ServerBinding]) = {
      val localAddressPromise = Promise[InetSocketAddress]()
      val unbindPromise = Promise[() ⇒ Future[Unit]]()
      val publisher = new Publisher[IncomingConnection] {

        override def subscribe(s: Subscriber[_ >: IncomingConnection]): Unit = {
          requireNonNullSubscriber(s)
          manager ! StreamTcpManager.Bind(
            localAddressPromise,
            unbindPromise,
            s.asInstanceOf[Subscriber[IncomingConnection]],
            endpoint,
            backlog,
            options,
            idleTimeout)
        }

      }

      import system.dispatcher
      val bindingFuture = unbindPromise.future.zip(localAddressPromise.future).map {
        case (unbindAction, localAddress) ⇒
          ServerBinding(localAddress)(unbindAction)
      }

      (publisher, bindingFuture)
    }

    override protected def newInstance(s: SourceShape[IncomingConnection]): SourceModule[IncomingConnection, Future[ServerBinding]] =
      new BindSource(endpoint, backlog, options, idleTimeout, attributes, shape)
    override def withAttributes(attr: OperationAttributes): Module =
      new BindSource(endpoint, backlog, options, idleTimeout, attr, shape)
  }

  /**
   * Creates a [[StreamTcp.ServerBinding]] instance which represents a prospective TCP server binding on the given `endpoint`.
   */
  def bind(interface: String,
           port: Int,
           backlog: Int = 100,
           options: immutable.Traversable[SocketOption] = Nil,
           idleTimeout: Duration = Duration.Inf): Source[IncomingConnection, Future[ServerBinding]] = {
    new Source(new BindSource(new InetSocketAddress(interface, port), backlog, options, idleTimeout,
      OperationAttributes.none, SourceShape(new Outlet("BindSource.out"))))
  }

  def bindAndHandle(
    handler: Flow[ByteString, ByteString, _],
    interface: String,
    port: Int,
    backlog: Int = 100,
    options: immutable.Traversable[SocketOption] = Nil,
    idleTimeout: Duration = Duration.Inf)(implicit m: FlowMaterializer): Future[ServerBinding] = {
    bind(interface, port, backlog, options, idleTimeout).to(Sink.foreach { conn: IncomingConnection ⇒
      conn.flow.join(handler).run()
    }).run()
  }

  /**
   * Creates an [[StreamTcp.OutgoingConnection]] instance representing a prospective TCP client connection to the given endpoint.
   */
  def outgoingConnection(remoteAddress: InetSocketAddress,
                         localAddress: Option[InetSocketAddress] = None,
                         options: immutable.Traversable[SocketOption] = Nil,
                         connectTimeout: Duration = Duration.Inf,
                         idleTimeout: Duration = Duration.Inf): Flow[ByteString, ByteString, Future[OutgoingConnection]] = {

    val remoteAddr = remoteAddress

    Flow[ByteString].andThenMat(() ⇒ {
      val processorPromise = Promise[Processor[ByteString, ByteString]]()
      val localAddressPromise = Promise[InetSocketAddress]()
      manager ! StreamTcpManager.Connect(processorPromise, localAddressPromise, remoteAddress, localAddress, options,
        connectTimeout, idleTimeout)
      import system.dispatcher
      val outgoingConnection = localAddressPromise.future.map(OutgoingConnection(remoteAddress, _))
      (new DelayedInitProcessor[ByteString, ByteString](processorPromise.future), outgoingConnection)
    })

  }

  /**
   * Creates an [[StreamTcp.OutgoingConnection]] without specifying options.
   * It represents a prospective TCP client connection to the given endpoint.
   */
  def outgoingConnection(host: String, port: Int): Flow[ByteString, ByteString, Future[OutgoingConnection]] =
    outgoingConnection(new InetSocketAddress(host, port))
}

