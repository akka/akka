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
     * The local address of the endpoint bound by the materialization of the `connections` [[Source]]
     * whose [[MaterializedMap]] is passed as parameter.
     */
    def localAddress(materializedMap: MaterializedMap): Future[InetSocketAddress] =
      delegate.localAddress(materializedMap.asScala)

    /**
     * The stream of accepted incoming connections.
     * Can be materialized several times but only one subscription can be "live" at one time, i.e.
     * subsequent materializations will reject subscriptions with an [[BindFailedException]] if the previous
     * materialization still has an uncancelled subscription.
     * Cancelling the subscription to a materialization of this source will cause the listening port to be unbound.
     */
    def connections: Source[IncomingConnection] =
      Source.adapt(delegate.connections.map(new IncomingConnection(_)))

    /**
     * Asynchronously triggers the unbinding of the port that was bound by the materialization of the `connections`
     * [[Source]] whose [[MaterializedMap]] is passed as parameter.
     *
     * The produced [[scala.concurrent.Future]] is fulfilled when the unbinding has been completed.
     */
    def unbind(materializedMap: MaterializedMap): Future[Unit] =
      delegate.unbind(materializedMap.asScala)
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
     * [[MaterializedMap]] returned.
     *
     * Convenience shortcut for: `flow.join(handler).run()`.
     */
    def handleWith(handler: Flow[ByteString, ByteString], materializer: FlowMaterializer): MaterializedMap =
      new MaterializedMap(delegate.handleWith(handler.asScala)(materializer))

    /**
     * A flow representing the client on the other side of the connection.
     * This flow can be materialized only once.
     */
    def flow: Flow[ByteString, ByteString] = Flow.adapt(delegate.flow)
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
     * The local address of the endpoint bound by the materialization of the connection materialization
     * whose [[MaterializedMap]] is passed as parameter.
     */
    def localAddress(mMap: MaterializedMap): Future[InetSocketAddress] =
      delegate.localAddress(mMap.asScala)

    /**
     * Handles the connection using the given flow.
     * This method can be called several times, every call will materialize the given flow exactly once thereby
     * triggering a new connection attempt to the `remoteAddress`.
     * If the connection cannot be established the materialized stream will immediately be terminated
     * with a [[akka.stream.StreamTcpException]].
     *
     * Convenience shortcut for: `flow.join(handler).run()`.
     */
    def handleWith(handler: Flow[ByteString, ByteString], materializer: FlowMaterializer): MaterializedMap =
      new MaterializedMap(delegate.handleWith(handler.asScala)(materializer))

    /**
     * A flow representing the server on the other side of the connection.
     * This flow can be materialized several times, every materialization will open a new connection to the
     * `remoteAddress`. If the connection cannot be established the materialized stream will immediately be terminated
     * with a [[akka.stream.StreamTcpException]].
     */
    def flow: Flow[ByteString, ByteString] = Flow.adapt(delegate.flow)
  }

  override def get(system: ActorSystem): StreamTcp = super.get(system)

  def lookup() = StreamTcp

  def createExtension(system: ExtendedActorSystem): StreamTcp = new StreamTcp(system)
}

class StreamTcp(system: ExtendedActorSystem) extends akka.actor.Extension {
  import StreamTcp._

  private lazy val delegate: scaladsl.StreamTcp = scaladsl.StreamTcp(system)

  /**
   * Creates a [[StreamTcp.ServerBinding]] instance which represents a prospective TCP server binding on the given `endpoint`.
   */
  def bind(endpoint: InetSocketAddress,
           backlog: Int,
           options: JIterable[SocketOption],
           idleTimeout: Duration): ServerBinding =
    new ServerBinding(delegate.bind(endpoint, backlog, immutableSeq(options), idleTimeout))

  /**
   * Creates a [[StreamTcp.ServerBinding]] without specifying options.
   * It represents a prospective TCP server binding on the given `endpoint`.
   */
  def bind(endpoint: InetSocketAddress): ServerBinding =
    new ServerBinding(delegate.bind(endpoint))

  /**
   * Creates an [[StreamTcp.OutgoingConnection]] instance representing a prospective TCP client connection to the given endpoint.
   */
  def outgoingConnection(remoteAddress: InetSocketAddress,
                         localAddress: Option[InetSocketAddress],
                         options: JIterable[SocketOption],
                         connectTimeout: Duration,
                         idleTimeout: Duration): OutgoingConnection =
    new OutgoingConnection(delegate.outgoingConnection(
      remoteAddress, localAddress, immutableSeq(options), connectTimeout, idleTimeout))

  /**
   * Creates an [[StreamTcp.OutgoingConnection]] without specifying options.
   * It represents a prospective TCP client connection to the given endpoint.
   */
  def outgoingConnection(remoteAddress: InetSocketAddress): OutgoingConnection =
    new OutgoingConnection(delegate.outgoingConnection(remoteAddress))

}
