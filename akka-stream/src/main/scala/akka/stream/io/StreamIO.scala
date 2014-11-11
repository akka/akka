/**
 * Copyright (C) 2009-2014 Typesafe Inc. <http://www.typesafe.com>
 */
package akka.stream.io

import java.io.Closeable
import java.net.{ InetSocketAddress, URLEncoder }

import akka.actor._
import akka.io.Inet.SocketOption
import akka.io.Tcp
import akka.pattern.ask
import akka.stream.MaterializerSettings
import akka.stream.impl._
import akka.stream.io.StreamTcp.{ IncomingTcpConnection, OutgoingTcpConnection, TcpServerBinding }
import akka.stream.scaladsl.{ Flow, KeyedActorFlowSource, Pipe, Source }
import akka.util.{ ByteString, Timeout }
import org.reactivestreams.{ Processor, Publisher, Subscriber, Subscription }

import scala.collection._
import scala.concurrent.duration._
import scala.concurrent.{ ExecutionContext, Future }
import scala.util.{ Failure, Success }

object StreamTcp extends ExtensionId[StreamTcpExt] with ExtensionIdProvider {

  override def lookup = StreamTcp
  override def createExtension(system: ExtendedActorSystem): StreamTcpExt = new StreamTcpExt(system)
  override def get(system: ActorSystem): StreamTcpExt = super.get(system)

  /**
   * INTERNAL API
   */
  private[akka] case class OutgoingTcpConnection(remoteAddress: InetSocketAddress,
                                                 localAddress: InetSocketAddress,
                                                 processor: Processor[ByteString, ByteString])

  /**
   * INTERNAL API
   */
  private[akka] sealed abstract case class TcpServerBinding(localAddress: InetSocketAddress,
                                                            connectionStream: Publisher[IncomingTcpConnection]) extends Closeable
  /**
   * INTERNAL API
   */
  private[akka] object TcpServerBinding {
    def apply(localAddress: InetSocketAddress, connectionStream: Publisher[IncomingTcpConnection]): TcpServerBinding =
      new TcpServerBinding(localAddress, connectionStream) {
        override def close() = ()
      }

    def apply(localAddress: InetSocketAddress, connectionStream: Publisher[IncomingTcpConnection], closeable: Closeable): TcpServerBinding =
      new TcpServerBinding(localAddress, connectionStream) {
        override def close() = closeable.close()
      }
  }

  case class IncomingTcpConnection(remoteAddress: InetSocketAddress, stream: Flow[ByteString, ByteString])

  /**
   * INTERNAL API
   */
  private[akka] case class Connect(remoteAddress: InetSocketAddress,
                                   localAddress: Option[InetSocketAddress] = None,
                                   materializerSettings: Option[MaterializerSettings] = None,
                                   options: immutable.Traversable[SocketOption] = Nil,
                                   connectTimeout: Duration = Duration.Inf,
                                   idleTimeout: Duration = Duration.Inf)
  /**
   * INTERNAL API
   */
  private[akka] case class Bind(localAddress: InetSocketAddress,
                                settings: Option[MaterializerSettings] = None,
                                backlog: Int = 100,
                                options: immutable.Traversable[SocketOption] = Nil,
                                idleTimeout: Duration = Duration.Inf)

}

/**
 * INTERNAL API
 */
private[akka] class DelayedInitProcessor[I, O](val implFuture: Future[Processor[I, O]])(implicit ec: ExecutionContext) extends Processor[I, O] {
  @volatile private var impl: Processor[I, O] = _
  private val setVarFuture = implFuture.andThen { case Success(p) ⇒ impl = p }

  override def onSubscribe(s: Subscription): Unit = implFuture.onComplete {
    case Success(impl) ⇒ impl.onSubscribe(s)
    case Failure(_)    ⇒ s.cancel()
  }

  override def onError(t: Throwable): Unit = {
    if (impl eq null) setVarFuture.onSuccess { case p ⇒ p.onError(t) }
    else impl.onError(t)
  }

  override def onComplete(): Unit = {
    if (impl eq null) setVarFuture.onSuccess { case p ⇒ p.onComplete() }
    else impl.onComplete()
  }

  override def onNext(t: I): Unit = impl.onNext(t)

  override def subscribe(s: Subscriber[_ >: O]): Unit = setVarFuture.onComplete {
    case Success(impl) ⇒ impl.subscribe(s)
    case Failure(e)    ⇒ s.onError(e)
  }
}

/**
 * INTERNAL API
 */
private[akka] class StreamTcpExt(val system: ExtendedActorSystem) extends Extension {
  private val manager: ActorRef = system.systemActorOf(Props[StreamTcpManager], name = "IO-TCP-STREAM")

  /**
   * Creates a Flow that represents a TCP connection to a remote host. The actual connection is only attempted
   * when the Flow is materialized. The returned Flow is reusable, each new materialization will attempt to open
   * a new connection to the remote host.
   *
   * @param remoteAddress the address to connect to
   * @param localAddress optionally specifies a specific address to bind to
   * @param options Please refer to [[akka.io.TcpSO]] for a list of all supported options.
   * @param connectTimeout the desired timeout for connection establishment, infinite means "no timeout"
   * @param idleTimeout the desired idle timeout on the connection, infinite means "no timeout"
   */
  def connect(remoteAddress: InetSocketAddress,
              localAddress: Option[InetSocketAddress] = None,
              options: immutable.Traversable[SocketOption] = Nil,
              connectTimeout: Duration = Duration.Inf,
              idleTimeout: Duration = Duration.Inf): Flow[ByteString, ByteString] = {
    implicit val t = Timeout(3.seconds)
    import system.dispatcher
    Pipe { () ⇒
      new DelayedInitProcessor[ByteString, ByteString](
        (StreamTcp(system).manager ? StreamTcp.Connect(remoteAddress, localAddress, None, options, connectTimeout, idleTimeout))
          .mapTo[OutgoingTcpConnection]
          .map(_.processor))
    }
  }

  /**
   * Returns a Source that represents a port listening to incoming connections. The actual binding to the local port
   * happens when the Source is first materialized. This Source is not reusable until the listen port becomes available
   * again.
   *
   * @param localAddress the socket address to bind to; use port zero for automatic assignment (i.e. an ephemeral port)
   * @param backlog the number of unaccepted connections the O/S
   *                kernel will hold for this port before refusing connections.
   * @param options Please refer to [[akka.io.TcpSO]] for a list of all supported options.
   * @param idleTimeout the desired idle timeout on the accepted connections, infinite means "no timeout"
   */
  def bind(localAddress: InetSocketAddress,
           backlog: Int = 100,
           options: immutable.Traversable[SocketOption] = Nil,
           idleTimeout: Duration = Duration.Inf): Source[IncomingTcpConnection] { type MaterializedType = Future[TcpServerBinding] } = {
    new KeyedActorFlowSource[IncomingTcpConnection] {
      implicit val t = Timeout(3.seconds)
      import system.dispatcher

      override def attach(flowSubscriber: Subscriber[IncomingTcpConnection], materializer: ActorBasedFlowMaterializer, flowName: String): MaterializedType = {
        val bindingFuture = (StreamTcp(system).manager ? StreamTcp.Bind(localAddress, None, backlog, options, idleTimeout))
          .mapTo[TcpServerBinding]

        bindingFuture.map(_.connectionStream).onComplete {
          case Success(impl) ⇒ impl.subscribe(flowSubscriber)
          case Failure(e)    ⇒ flowSubscriber.onError(e)
        }

        bindingFuture
      }

      override type MaterializedType = Future[TcpServerBinding]
    }
  }

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
  import akka.stream.io.StreamTcpManager._

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

