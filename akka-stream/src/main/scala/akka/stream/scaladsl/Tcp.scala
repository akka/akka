/**
 * Copyright (C) 2009-2016 Lightbend Inc. <http://www.lightbend.com>
 */
package akka.stream.scaladsl

import java.net.InetSocketAddress

import akka.NotUsed
import akka.actor._
import akka.io.Inet.SocketOption
import akka.io.{ IO, Tcp ⇒ IoTcp }
import akka.stream._
import akka.stream.impl.fusing.GraphStages.detacher
import akka.stream.impl.io.{ ConnectionSourceStage, OutgoingConnectionStage }
import akka.util.ByteString

import scala.collection.immutable
import scala.concurrent.Future
import scala.concurrent.duration.{ Duration, FiniteDuration }

object Tcp extends ExtensionId[Tcp] with ExtensionIdProvider {

  /**
   * * Represents a successful TCP server binding.
   */
  final case class ServerBinding(localAddress: InetSocketAddress)(private val unbindAction: () ⇒ Future[Unit]) {
    def unbind(): Future[Unit] = unbindAction()
  }

  /**
   * Represents an accepted incoming TCP connection.
   */
  final case class IncomingConnection(
    localAddress: InetSocketAddress,
    remoteAddress: InetSocketAddress,
    flow: Flow[ByteString, ByteString, NotUsed]) {

    /**
     * Handles the connection using the given flow, which is materialized exactly once and the respective
     * materialized instance is returned.
     *
     * Convenience shortcut for: `flow.join(handler).run()`.
     */
    def handleWith[Mat](handler: Flow[ByteString, ByteString, Mat])(implicit materializer: Materializer): Mat =
      flow.joinMat(handler)(Keep.right).run()

  }

  /**
   * Represents a prospective outgoing TCP connection.
   */
  final case class OutgoingConnection(remoteAddress: InetSocketAddress, localAddress: InetSocketAddress)

  def apply()(implicit system: ActorSystem): Tcp = super.apply(system)

  override def get(system: ActorSystem): Tcp = super.get(system)

  def lookup() = Tcp

  def createExtension(system: ExtendedActorSystem): Tcp = new Tcp(system)
}

final class Tcp(system: ExtendedActorSystem) extends akka.actor.Extension {
  import Tcp._

  // TODO maybe this should be a new setting, like `akka.stream.tcp.bind.timeout` / `shutdown-timeout` instead?
  val bindShutdownTimeout = ActorMaterializer()(system).settings.subscriptionTimeoutSettings.timeout

  /**
   * Creates a [[Tcp.ServerBinding]] instance which represents a prospective TCP server binding on the given `endpoint`.
   *
   * Please note that the startup of the server is asynchronous, i.e. after materializing the enclosing
   * [[akka.stream.scaladsl.RunnableGraph]] the server is not immediately available. Only after the materialized future
   * completes is the server ready to accept client connections.
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
           backlog: Int = 100,
           options: immutable.Traversable[SocketOption] = Nil,
           halfClose: Boolean = false,
           idleTimeout: Duration = Duration.Inf): Source[IncomingConnection, Future[ServerBinding]] =
    Source.fromGraph(new ConnectionSourceStage(
      IO(IoTcp)(system),
      new InetSocketAddress(interface, port),
      backlog,
      options,
      halfClose,
      idleTimeout,
      bindShutdownTimeout))

  /**
   * Creates a [[Tcp.ServerBinding]] instance which represents a prospective TCP server binding on the given `endpoint`
   * handling the incoming connections using the provided Flow.
   *
   * Please note that the startup of the server is asynchronous, i.e. after materializing the enclosing
   * [[akka.stream.scaladsl.RunnableGraph]] the server is not immediately available. Only after the returned future
   * completes is the server ready to accept client connections.
   *
   * @param handler   A Flow that represents the server logic
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
  def bindAndHandle(
    handler: Flow[ByteString, ByteString, _],
    interface: String,
    port: Int,
    backlog: Int = 100,
    options: immutable.Traversable[SocketOption] = Nil,
    halfClose: Boolean = false,
    idleTimeout: Duration = Duration.Inf)(implicit m: Materializer): Future[ServerBinding] = {
    bind(interface, port, backlog, options, halfClose, idleTimeout).to(Sink.foreach { conn: IncomingConnection ⇒
      conn.flow.join(handler).run()
    }).run()
  }

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
                         localAddress: Option[InetSocketAddress] = None,
                         options: immutable.Traversable[SocketOption] = Nil,
                         halfClose: Boolean = true,
                         connectTimeout: Duration = Duration.Inf,
                         idleTimeout: Duration = Duration.Inf): Flow[ByteString, ByteString, Future[OutgoingConnection]] = {

    val tcpFlow = Flow.fromGraph(new OutgoingConnectionStage(
      IO(IoTcp)(system),
      remoteAddress,
      localAddress,
      options,
      halfClose,
      connectTimeout)).via(detacher[ByteString]) // must read ahead for proper completions

    idleTimeout match {
      case d: FiniteDuration ⇒ tcpFlow.join(BidiFlow.bidirectionalIdleTimeout[ByteString, ByteString](d))
      case _                 ⇒ tcpFlow
    }

  }

  /**
   * Creates an [[Tcp.OutgoingConnection]] without specifying options.
   * It represents a prospective TCP client connection to the given endpoint.
   */
  def outgoingConnection(host: String, port: Int): Flow[ByteString, ByteString, Future[OutgoingConnection]] =
    outgoingConnection(new InetSocketAddress(host, port))
}
