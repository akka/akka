/**
 * Copyright (C) 2014 Typesafe Inc. <http://www.typesafe.com>
 */
package akka.stream.javadsl

import java.lang.{ Iterable ⇒ JIterable }
import scala.collection.immutable
import scala.concurrent.duration._
import java.net.InetSocketAddress
import scala.concurrent.Future
import scala.util.control.NoStackTrace
import akka.actor.ActorSystem
import akka.actor.ExtendedActorSystem
import akka.actor.ExtensionId
import akka.actor.ExtensionIdProvider
import akka.stream.FlowMaterializer
import akka.stream.scaladsl
import akka.util.ByteString
import akka.japi.Util.immutableSeq
import akka.io.Inet.SocketOption

object StreamTcp extends ExtensionId[StreamTcp] with ExtensionIdProvider {

  /**
   * Represents a prospective TCP server binding.
   */
  class ServerBinding private[akka] (delegate: scaladsl.StreamTcp.ServerBinding) {
    /**
     * The local address of the endpoint bound by the materialization of the `connections` [[Source]].
     */
    def localAddress: InetSocketAddress = delegate.localAddress

    /**
     * Asynchronously triggers the unbinding of the port that was bound by the materialization of the `connections`
     * [[Source]].
     *
     * The produced [[scala.concurrent.Future]] is fulfilled when the unbinding has been completed.
     */
    def unbind(): Future[Unit] = delegate.unbind
  }

  /**
   * Represents an accepted incoming TCP connection.
   */
  class IncomingConnection private[akka] (delegate: scaladsl.StreamTcp.IncomingConnection) {
    /**
     * The local address this connection is bound to.
     */
    def localAddress: InetSocketAddress = delegate.localAddress

    /**
     * The remote address this connection is bound to.
     */
    def remoteAddress: InetSocketAddress = delegate.remoteAddress

    /**
     * Handles the connection using the given flow, which is materialized exactly once and the respective
     * materialized value is returned.
     *
     * Convenience shortcut for: `flow.join(handler).run()`.
     */
    def handleWith[Mat](handler: Flow[ByteString, ByteString, Mat], materializer: FlowMaterializer): Mat =
      delegate.handleWith(handler.asScala)(materializer)

    /**
     * A flow representing the client on the other side of the connection.
     * This flow can be materialized only once.
     */
    def flow: Flow[ByteString, ByteString, Unit] = Flow.adapt(delegate.flow)
  }

  /**
   * Represents a prospective outgoing TCP connection.
   */
  class OutgoingConnection private[akka] (delegate: scaladsl.StreamTcp.OutgoingConnection) {
    /**
     * The remote address this connection is or will be bound to.
     */
    def remoteAddress: InetSocketAddress = delegate.remoteAddress

    /**
     * The local address of the endpoint bound by the materialization of the connection materialization.
     */
    def localAddress: InetSocketAddress = delegate.localAddress
  }

  override def get(system: ActorSystem): StreamTcp = super.get(system)

  def lookup() = StreamTcp

  def createExtension(system: ExtendedActorSystem): StreamTcp = new StreamTcp(system)
}

class StreamTcp(system: ExtendedActorSystem) extends akka.actor.Extension {
  import StreamTcp._
  import akka.dispatch.ExecutionContexts.{ sameThreadExecutionContext ⇒ ec }

  private lazy val delegate: scaladsl.StreamTcp = scaladsl.StreamTcp(system)

  /**
   * Creates a [[StreamTcp.ServerBinding]] instance which represents a prospective TCP server binding on the given `endpoint`.
   */
  def bind(interface: String,
           port: Int,
           backlog: Int,
           options: JIterable[SocketOption],
           idleTimeout: Duration): Source[IncomingConnection, Future[ServerBinding]] =
    Source.adapt(delegate.bind(interface, port, backlog, immutableSeq(options), idleTimeout)
      .map(new IncomingConnection(_))
      .mapMaterialized(_.map(new ServerBinding(_))(ec)))

  /**
   * Creates a [[StreamTcp.ServerBinding]] without specifying options.
   * It represents a prospective TCP server binding on the given `endpoint`.
   */
  def bind(interface: String, port: Int): Source[IncomingConnection, Future[ServerBinding]] =
    Source.adapt(delegate.bind(interface, port)
      .map(new IncomingConnection(_))
      .mapMaterialized(_.map(new ServerBinding(_))(ec)))

  /**
   * Creates an [[StreamTcp.OutgoingConnection]] instance representing a prospective TCP client connection to the given endpoint.
   */
  def outgoingConnection(remoteAddress: InetSocketAddress,
                         localAddress: Option[InetSocketAddress],
                         options: JIterable[SocketOption],
                         connectTimeout: Duration,
                         idleTimeout: Duration): Flow[ByteString, ByteString, Future[OutgoingConnection]] =
    Flow.adapt(delegate.outgoingConnection(remoteAddress, localAddress, immutableSeq(options), connectTimeout, idleTimeout)
      .mapMaterialized(_.map(new OutgoingConnection(_))(ec)))

  /**
   * Creates an [[StreamTcp.OutgoingConnection]] without specifying options.
   * It represents a prospective TCP client connection to the given endpoint.
   */
  def outgoingConnection(host: String, port: Int): Flow[ByteString, ByteString, Future[OutgoingConnection]] =
    Flow.adapt(delegate.outgoingConnection(new InetSocketAddress(host, port))
      .mapMaterialized(_.map(new OutgoingConnection(_))(ec)))

}
