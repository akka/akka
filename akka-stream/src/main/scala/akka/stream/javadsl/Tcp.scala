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
import akka.stream.Materializer
import akka.stream.scaladsl
import akka.util.ByteString
import akka.japi.Util.immutableSeq
import akka.io.Inet.SocketOption

object Tcp extends ExtensionId[Tcp] with ExtensionIdProvider {

  /**
   * Represents a prospective TCP server binding.
   */
  class ServerBinding private[akka] (delegate: scaladsl.Tcp.ServerBinding) {
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
  class IncomingConnection private[akka] (delegate: scaladsl.Tcp.IncomingConnection) {
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
    def handleWith[Mat](handler: Flow[ByteString, ByteString, Mat], materializer: Materializer): Mat =
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
  class OutgoingConnection private[akka] (delegate: scaladsl.Tcp.OutgoingConnection) {
    /**
     * The remote address this connection is or will be bound to.
     */
    def remoteAddress: InetSocketAddress = delegate.remoteAddress

    /**
     * The local address of the endpoint bound by the materialization of the connection materialization.
     */
    def localAddress: InetSocketAddress = delegate.localAddress
  }

  override def get(system: ActorSystem): Tcp = super.get(system)

  def lookup() = Tcp

  def createExtension(system: ExtendedActorSystem): Tcp = new Tcp(system)
}

class Tcp(system: ExtendedActorSystem) extends akka.actor.Extension {
  import Tcp._
  import akka.dispatch.ExecutionContexts.{ sameThreadExecutionContext ⇒ ec }

  private lazy val delegate: scaladsl.Tcp = scaladsl.Tcp(system)

  /**
   * Creates a [[Tcp.ServerBinding]] instance which represents a prospective TCP server binding on the given `endpoint`.
   *
   * @param interface The interface to listen on
   * @param port      The port to listen on
   * @param backlog   Controls the size of the connection backlog
   * @param options   TCP options for the connections, see [[akka.io.Tcp]] for details
   * @param halfClose
   *                  Controls whether the connection is kept open even after writing has been completed to the accepted
   *                  TCP connections.
   *                  If set to true, the connection will implement the TCP half-close mechanism, allowing the client to
   *                  write to the connection even after the server has finished writing. The TCP socket is only closed
   *                  after both the client and server finished writing.
   *                  If set to false, the connection will immediately closed once the server closes its write side,
   *                  independently whether the client is still attempting to write. This setting is recommended
   *                  for servers, and therefore it is the default setting.
   */
  def bind(interface: String,
           port: Int,
           backlog: Int,
           options: JIterable[SocketOption],
           halfClose: Boolean,
           idleTimeout: Duration): Source[IncomingConnection, Future[ServerBinding]] =
    Source.adapt(delegate.bind(interface, port, backlog, immutableSeq(options), halfClose, idleTimeout)
      .map(new IncomingConnection(_))
      .mapMaterializedValue(_.map(new ServerBinding(_))(ec)))

  /**
   * Creates a [[Tcp.ServerBinding]] without specifying options.
   * It represents a prospective TCP server binding on the given `endpoint`.
   */
  def bind(interface: String, port: Int): Source[IncomingConnection, Future[ServerBinding]] =
    Source.adapt(delegate.bind(interface, port)
      .map(new IncomingConnection(_))
      .mapMaterializedValue(_.map(new ServerBinding(_))(ec)))

  /**
   * Creates an [[Tcp.OutgoingConnection]] instance representing a prospective TCP client connection to the given endpoint.
   *
   * @param remoteAddress The remote address to connect to
   * @param localAddress  Optional local address for the connection
   * @param options   TCP options for the connections, see [[akka.io.Tcp]] for details
   * @param halfClose
   *                  Controls whether the connection is kept open even after writing has been completed to the accepted
   *                  TCP connections.
   *                  If set to true, the connection will implement the TCP half-close mechanism, allowing the server to
   *                  write to the connection even after the client has finished writing. The TCP socket is only closed
   *                  after both the client and server finished writing. This setting is recommended for clients and
   *                  therefore it is the default setting.
   *                  If set to false, the connection will immediately closed once the client closes its write side,
   *                  independently whether the server is still attempting to write.
   */
  def outgoingConnection(remoteAddress: InetSocketAddress,
                         localAddress: Option[InetSocketAddress],
                         options: JIterable[SocketOption],
                         halfClose: Boolean,
                         connectTimeout: Duration,
                         idleTimeout: Duration): Flow[ByteString, ByteString, Future[OutgoingConnection]] =
    Flow.adapt(delegate.outgoingConnection(remoteAddress, localAddress, immutableSeq(options), halfClose, connectTimeout, idleTimeout)
      .mapMaterializedValue(_.map(new OutgoingConnection(_))(ec)))

  /**
   * Creates an [[Tcp.OutgoingConnection]] without specifying options.
   * It represents a prospective TCP client connection to the given endpoint.
   */
  def outgoingConnection(host: String, port: Int): Flow[ByteString, ByteString, Future[OutgoingConnection]] =
    Flow.adapt(delegate.outgoingConnection(new InetSocketAddress(host, port))
      .mapMaterializedValue(_.map(new OutgoingConnection(_))(ec)))

}
