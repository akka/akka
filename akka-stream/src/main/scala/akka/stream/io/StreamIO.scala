/**
 * Copyright (C) 2009-2014 Typesafe Inc. <http://www.typesafe.com>
 */
package akka.stream.io

import akka.util.ByteString
import org.reactivestreams.api.{ Processor, Producer, Consumer }
import java.net.InetSocketAddress
import akka.actor._
import scala.collection._
import scala.concurrent.duration.FiniteDuration
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
    def outputStream: Consumer[ByteString] = processor
    def inputStream: Producer[ByteString] = processor
  }

  case class TcpServerBinding(localAddress: InetSocketAddress,
                              connectionStream: Producer[IncomingTcpConnection])

  case class IncomingTcpConnection(remoteAddress: InetSocketAddress,
                                   inputStream: Producer[ByteString],
                                   outputStream: Consumer[ByteString]) {
    def handleWith(processor: Processor[ByteString, ByteString]): Unit = {
      processor.produceTo(outputStream)
      inputStream.produceTo(processor)
    }
  }

  /**
   * The Connect message is sent to the StreamTcp manager actor, which is obtained via
   * `IO(StreamTcp)`. The manager replies with a [[StreamTcp.OutgoingTcpConnection]]
   * message.
   *
   * @param remoteAddress is the address to connect to
   * @param localAddress optionally specifies a specific address to bind to
   * @param options Please refer to [[akka.io.TcpSO]] for a list of all supported options.
   * @param timeout is the desired connection timeout, `null` means "no timeout"
   */
  case class Connect(settings: MaterializerSettings,
                     remoteAddress: InetSocketAddress,
                     localAddress: Option[InetSocketAddress] = None,
                     options: immutable.Traversable[SocketOption] = Nil,
                     timeout: Option[FiniteDuration] = None) {

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
    def withTimeout(timeout: FiniteDuration): Connect =
      copy(timeout = Option(timeout))
  }

  /**
   * The Bind message is send to the StreamTcp manager actor, which is obtained via
   * `IO(StreamTcp)`, in order to bind to a listening socket. The manager
   * replies with a [[StreamTcp.TcpServerBinding]]. If the local port is set to 0 in
   * the Bind message, then the [[StreamTcp.TcpServerBinding]] message should be inspected to find
   * the actual port which was bound to.
   *
   * @param localAddress The socket address to bind to; use port zero for
   *                automatic assignment (i.e. an ephemeral port)
   *
   * @param backlog This specifies the number of unaccepted connections the O/S
   *                kernel will hold for this port before refusing connections.
   *
   * @param options Please refer to [[akka.io.TcpSO]] for a list of all supported options.
   */
  case class Bind(settings: MaterializerSettings,
                  localAddress: InetSocketAddress,
                  backlog: Int = 100,
                  options: immutable.Traversable[SocketOption] = Nil) {

    /**
     * Java API
     */
    def withBacklog(backlog: Int): Bind = copy(backlog = backlog)

    /**
     * Java API
     */
    def withSocketOptions(options: java.lang.Iterable[SocketOption]): Bind =
      copy(options = Util.immutableSeq(options))

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
   * @param timeout is the desired connection timeout, `null` means "no timeout"
   */
  def connect(
    settings: MaterializerSettings,
    remoteAddress: InetSocketAddress,
    localAddress: InetSocketAddress,
    options: java.lang.Iterable[SocketOption],
    timeout: FiniteDuration): StreamTcp.Connect =
    StreamTcp.Connect(settings, remoteAddress, Option(localAddress), Util.immutableSeq(options), Option(timeout))

  /**
   * Java API: Message to Connect to the given `remoteAddress` without binding to a local address and without
   * specifying options.
   */
  def connect(settings: MaterializerSettings, remoteAddress: InetSocketAddress): StreamTcp.Connect =
    StreamTcp.Connect(settings, remoteAddress)

  /**
   * Java API: The Bind message is send to the StreamTcp manager actor, which is obtained via
   * `StreamTcp.get(system).manager()`, in order to bind to a listening socket. The manager
   * replies with a [[StreamTcp.TcpServerBinding]]. If the local port is set to 0 in
   * the Bind message, then the [[StreamTcp.TcpServerBinding]] message should be inspected to find
   * the actual port which was bound to.
   *
   * @param localAddress The socket address to bind to; use port zero for
   *                automatic assignment (i.e. an ephemeral port)
   *
   * @param backlog This specifies the number of unaccepted connections the O/S
   *                kernel will hold for this port before refusing connections.
   *
   * @param options Please refer to [[akka.io.TcpSO]] for a list of all supported options.
   */
  def bind(settings: MaterializerSettings,
           localAddress: InetSocketAddress,
           backlog: Int,
           options: java.lang.Iterable[SocketOption]): StreamTcp.Bind =
    StreamTcp.Bind(settings, localAddress, backlog, Util.immutableSeq(options))

  /**
   * Java API: Message to open a listening socket without specifying options.
   */
  def bind(settings: MaterializerSettings,
           localAddress: InetSocketAddress): StreamTcp.Bind =
    StreamTcp.Bind(settings, localAddress)
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
    case StreamTcp.Connect(settings, remoteAddress, localAddress, options, timeout) ⇒
      val processorActor = context.actorOf(TcpStreamActor.outboundProps(
        Tcp.Connect(remoteAddress, localAddress, options, timeout, pullMode = true),
        requester = sender(),
        settings), name = encName("client", remoteAddress))
      processorActor ! ExposedProcessor(new ActorProcessor[ByteString, ByteString](processorActor))

    case StreamTcp.Bind(settings, localAddress, backlog, options) ⇒
      val publisherActor = context.actorOf(TcpListenStreamActor.props(
        Tcp.Bind(context.system.deadLetters, localAddress, backlog, options, pullMode = true),
        requester = sender(),
        settings), name = encName("server", localAddress))
      publisherActor ! ExposedPublisher(new ActorPublisher(publisherActor))
  }

}

