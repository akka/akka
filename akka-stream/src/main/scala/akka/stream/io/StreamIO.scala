/**
 * Copyright (C) 2009-2014 Typesafe Inc. <http://www.typesafe.com>
 */
package akka.stream.io

import akka.util.ByteString
import org.reactivestreams.{ Processor, Publisher, Subscriber }
import java.net.InetSocketAddress
import akka.actor._
import scala.collection._
import scala.concurrent.duration.{ Duration, FiniteDuration }
import akka.io.Inet.SocketOption
import akka.io.Tcp
import akka.stream.impl.{ ActorPublisher, ExposedPublisher, ActorProcessor }
import akka.stream.MaterializerSettings
import akka.io.IO
import java.net.URLEncoder
import akka.japi.Util

object StreamTcp extends ExtensionId[StreamTcpExt] with ExtensionIdProvider {

  override def lookup = StreamTcp
  override def createExtension(system: ExtendedActorSystem): StreamTcpExt = new StreamTcpExt(system)
  override def get(system: ActorSystem): StreamTcpExt = super.get(system)

  case class OutgoingTcpConnection(remoteAddress: InetSocketAddress,
                                   localAddress: InetSocketAddress,
                                   processor: Processor[ByteString, ByteString]) {
    def outputStream: Subscriber[ByteString] = processor
    def inputStream: Publisher[ByteString] = processor
  }

  case class TcpServerBinding(localAddress: InetSocketAddress,
                              connectionStream: Publisher[IncomingTcpConnection])

  case class IncomingTcpConnection(remoteAddress: InetSocketAddress,
                                   inputStream: Publisher[ByteString],
                                   outputStream: Subscriber[ByteString]) {
    def handleWith(processor: Processor[ByteString, ByteString]): Unit = {
      processor.subscribe(outputStream)
      inputStream.subscribe(processor)
    }
  }

  /**
   * The Connect message is sent to the StreamTcp manager actor, which is obtained via
   * `IO(StreamTcp)`. The manager replies with a [[StreamTcp.OutgoingTcpConnection]]
   * message.
   *
   * @param remoteAddress the address to connect to
   * @param settings if Some the passed [[MaterializerSettings]] will be used during stream actor creation,
   *                 otherwise the ActorSystem's default settings will be used
   * @param localAddress optionally specifies a specific address to bind to
   * @param options Please refer to [[akka.io.TcpSO]] for a list of all supported options.
   * @param connectTimeout the desired timeout for connection establishment, infinite means "no timeout"
   * @param idleTimeout the desired idle timeout on the connection, infinite means "no timeout"
   */
  case class Connect(remoteAddress: InetSocketAddress,
                     localAddress: Option[InetSocketAddress] = None,
                     materializerSettings: Option[MaterializerSettings] = None,
                     options: immutable.Traversable[SocketOption] = Nil,
                     connectTimeout: Duration = Duration.Inf,
                     idleTimeout: Duration = Duration.Inf) {
    /**
     * Java API
     */
    def withMaterializerSettings(materializerSettings: MaterializerSettings): Connect =
      copy(materializerSettings = Option(materializerSettings))

    /**
     * Java API
     */
    def withLocalAddress(localAddress: InetSocketAddress): Connect =
      copy(localAddress = Option(localAddress))

    /**
     * Java API
     */
    def withSocketOptions(options: java.lang.Iterable[SocketOption]): Connect =
      copy(options = Util.immutableSeq(options))

    /**
     * Java API
     */
    def withConnectTimeout(connectTimeout: Duration): Connect =
      copy(connectTimeout = connectTimeout)

    /**
     * Java API
     */
    def withIdleTimeout(idleTimeout: Duration): Connect =
      copy(idleTimeout = idleTimeout)
  }

  /**
   * The Bind message is send to the StreamTcp manager actor, which is obtained via
   * `IO(StreamTcp)`, in order to bind to a listening socket. The manager
   * replies with a [[StreamTcp.TcpServerBinding]]. If the local port is set to 0 in
   * the Bind message, then the [[StreamTcp.TcpServerBinding]] message should be inspected to find
   * the actual port which was bound to.
   *
   * @param settings if Some, these materializer settings will be used for stream actors,
   *                 else the ActorSystem's default materializer settings will be used.
   * @param localAddress the socket address to bind to; use port zero for automatic assignment (i.e. an ephemeral port)
   * @param backlog the number of unaccepted connections the O/S
   *                kernel will hold for this port before refusing connections.
   * @param options Please refer to [[akka.io.TcpSO]] for a list of all supported options.
   * @param idleTimeout the desired idle timeout on the accepted connections, infinite means "no timeout"
   */
  case class Bind(localAddress: InetSocketAddress,
                  settings: Option[MaterializerSettings] = None,
                  backlog: Int = 100,
                  options: immutable.Traversable[SocketOption] = Nil,
                  idleTimeout: Duration = Duration.Inf) {

    /**
     * Java API
     */
    def withBacklog(backlog: Int): Bind = copy(backlog = backlog)

    /**
     * Java API
     */
    def withSocketOptions(options: java.lang.Iterable[SocketOption]): Bind =
      copy(options = Util.immutableSeq(options))

    /**
     * Java API
     */
    def withIdleTimeout(idleTimeout: Duration): Bind =
      copy(idleTimeout = idleTimeout)
  }

}

/**
 * Java API: Factory methods for the messages of `StreamTcp`.
 */
object StreamTcpMessage {
  /**
   * Java API: The Connect message is sent to the StreamTcp manager actor, which is obtained via
   * `StreamTcp.get(system).manager()`. The manager replies with a [[StreamTcp.OutgoingTcpConnection]]
   * message.
   *
   * @param remoteAddress is the address to connect to
   * @param localAddress optionally specifies a specific address to bind to
   * @param options Please refer to [[akka.io.TcpSO]] for a list of all supported options.
   * @param connectTimeout the desired timeout for connection establishment, infinite means "no timeout"
   * @param idleTimeout the desired idle timeout on the connection, infinite means "no timeout"
   */
  def connect(
    settings: MaterializerSettings,
    remoteAddress: InetSocketAddress,
    localAddress: InetSocketAddress,
    options: java.lang.Iterable[SocketOption],
    connectTimeout: Duration,
    idleTimeout: Duration): StreamTcp.Connect =
    StreamTcp.Connect(remoteAddress, Option(localAddress), Option(settings), Util.immutableSeq(options), connectTimeout, idleTimeout)

  /**
   * Java API: Message to Connect to the given `remoteAddress` without binding to a local address and without
   * specifying options.
   */
  def connect(settings: MaterializerSettings, remoteAddress: InetSocketAddress): StreamTcp.Connect =
    StreamTcp.Connect(remoteAddress, materializerSettings = Option(settings))

  /**
   * Java API: The Bind message is send to the StreamTcp manager actor, which is obtained via
   * `StreamTcp.get(system).manager()`, in order to bind to a listening socket. The manager
   * replies with a [[StreamTcp.TcpServerBinding]]. If the local port is set to 0 in
   * the Bind message, then the [[StreamTcp.TcpServerBinding]] message should be inspected to find
   * the actual port which was bound to.
   *
   * @param settings if Some, these materializer settings will be used for stream actors,
   *                 else the ActorSystem's default materializer settings will be used.
   * @param localAddress the socket address to bind to; use port zero for automatic assignment (i.e. an ephemeral port)
   * @param backlog the number of unaccepted connections the O/S
   *                kernel will hold for this port before refusing connections.
   * @param options Please refer to [[akka.io.TcpSO]] for a list of all supported options.
   * @param idleTimeout the desired idle timeout on the accepted connections, infinite means "no timeout"
   */
  def bind(settings: MaterializerSettings,
           localAddress: InetSocketAddress,
           backlog: Int,
           options: java.lang.Iterable[SocketOption],
           idleTimeout: Duration): StreamTcp.Bind =
    StreamTcp.Bind(localAddress, Option(settings), backlog, Util.immutableSeq(options), idleTimeout)

  /**
   * Java API: Message to open a listening socket without specifying options.
   */
  def bind(settings: MaterializerSettings, localAddress: InetSocketAddress): StreamTcp.Bind =
    StreamTcp.Bind(localAddress, Option(settings))
}

/**
 * INTERNAL API
 */
private[akka] class StreamTcpExt(system: ExtendedActorSystem) extends IO.Extension {
  val manager: ActorRef = system.systemActorOf(Props[StreamTcpManager], name = "IO-TCP-STREAM")
}

/**
 * INTERNAL API
 */
private[akka] object StreamTcpManager {
  private[akka] case class ExposedProcessor(processor: Processor[ByteString, ByteString])
}

/**
 * INTERNAL API
 */
private[akka] class StreamTcpManager extends Actor {
  import StreamTcpManager._

  var nameCounter = 0
  def encName(prefix: String, address: InetSocketAddress) = {
    nameCounter += 1
    s"$prefix-$nameCounter-${URLEncoder.encode(address.toString, "utf-8")}"
  }

  def receive: Receive = {
    case StreamTcp.Connect(remoteAddress, localAddress, maybeMaterializerSettings, options, connectTimeout, idleTimeout) ⇒
      val connTimeout = connectTimeout match {
        case x: FiniteDuration ⇒ Some(x)
        case _                 ⇒ None
      }
      val materializerSettings = maybeMaterializerSettings getOrElse MaterializerSettings(context.system)

      val processorActor = context.actorOf(TcpStreamActor.outboundProps(
        Tcp.Connect(remoteAddress, localAddress, options, connTimeout, pullMode = true),
        requester = sender(),
        settings = materializerSettings), name = encName("client", remoteAddress))
      processorActor ! ExposedProcessor(ActorProcessor[ByteString, ByteString](processorActor))

    case StreamTcp.Bind(localAddress, maybeMaterializerSettings, backlog, options, idleTimeout) ⇒
      val materializerSettings = maybeMaterializerSettings getOrElse MaterializerSettings(context.system)

      val publisherActor = context.actorOf(TcpListenStreamActor.props(
        Tcp.Bind(context.system.deadLetters, localAddress, backlog, options, pullMode = true),
        requester = sender(),
        materializerSettings), name = encName("server", localAddress))
      publisherActor ! ExposedPublisher(ActorPublisher[Any](publisherActor))
  }
}

