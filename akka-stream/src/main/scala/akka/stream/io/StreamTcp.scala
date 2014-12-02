/**
 * Copyright (C) 2009-2014 Typesafe Inc. <http://www.typesafe.com>
 */
package akka.stream.io

import java.net.{ InetSocketAddress, URLEncoder }
import org.reactivestreams.{ Processor, Subscriber, Subscription }
import scala.util.control.NoStackTrace
import scala.util.{ Failure, Success }
import scala.collection.immutable
import scala.concurrent.duration._
import scala.concurrent.{ Promise, ExecutionContext, Future }
import akka.util.ByteString
import akka.io.Inet.SocketOption
import akka.io.Tcp
import akka.stream.{ FlowMaterializer, MaterializerSettings }
import akka.stream.scaladsl._
import akka.stream.impl._
import akka.actor._

object StreamTcp extends ExtensionId[StreamTcpExt] with ExtensionIdProvider {

  /**
   * Represents a prospective TCP server binding.
   */
  trait ServerBinding {
    /**
     * The local address of the endpoint bound by the materialization of the `connections` [[Source]]
     * whose [[MaterializedMap]] is passed as parameter.
     */
    def localAddress(materializedMap: MaterializedMap): Future[InetSocketAddress]

    /**
     * The stream of accepted incoming connections.
     * Can be materialized several times but only one subscription can be "live" at one time, i.e.
     * subsequent materializations will reject subscriptions with an [[BindFailedException]] if the previous
     * materialization still has an uncancelled subscription.
     * Cancelling the subscription to a materialization of this source will cause the listening port to be unbound.
     */
    def connections: Source[IncomingConnection]

    /**
     * Asynchronously triggers the unbinding of the port that was bound by the materialization of the `connections`
     * [[Source]] whose [[MaterializedMap]] is passed as parameter.
     *
     * The produced [[Future]] is fulfilled when the unbinding has been completed.
     */
    def unbind(materializedMap: MaterializedMap): Future[Unit]
  }

  /**
   * Represents an accepted incoming TCP connection.
   */
  trait IncomingConnection {
    /**
     * The local address this connection is bound to.
     */
    def localAddress: InetSocketAddress

    /**
     * The remote address this connection is bound to.
     */
    def remoteAddress: InetSocketAddress

    /**
     * Handles the connection using the given flow, which is materialized exactly once and the respective
     * [[MaterializedMap]] returned.
     *
     * Convenience shortcut for: `flow.join(handler).run()`.
     */
    def handleWith(handler: Flow[ByteString, ByteString])(implicit materializer: FlowMaterializer): MaterializedMap

    /**
     * A flow representing the client on the other side of the connection.
     * This flow can be materialized only once.
     */
    def flow: Flow[ByteString, ByteString]
  }

  /**
   * Represents a prospective outgoing TCP connection.
   */
  sealed trait OutgoingConnection {
    /**
     * The remote address this connection is or will be bound to.
     */
    def remoteAddress: InetSocketAddress

    /**
     * The local address of the endpoint bound by the materialization of the connection materialization
     * whose [[MaterializedMap]] is passed as parameter.
     */
    def localAddress(mMap: MaterializedMap): Future[InetSocketAddress]

    /**
     * Handles the connection using the given flow.
     * This method can be called several times, every call will materialize the given flow exactly once thereby
     * triggering a new connection attempt to the `remoteAddress`.
     * If the connection cannot be established the materialized stream will immediately be terminated
     * with a [[ConnectionAttemptFailedException]].
     *
     * Convenience shortcut for: `flow.join(handler).run()`.
     */
    def handleWith(handler: Flow[ByteString, ByteString])(implicit materializer: FlowMaterializer): MaterializedMap

    /**
     * A flow representing the server on the other side of the connection.
     * This flow can be materialized several times, every materialization will open a new connection to the
     * `remoteAddress`. If the connection cannot be established the materialized stream will immediately be terminated
     * with a [[ConnectionAttemptFailedException]].
     */
    def flow: Flow[ByteString, ByteString]
  }

  case object BindFailedException extends RuntimeException with NoStackTrace

  class ConnectionException(message: String) extends RuntimeException(message)

  class ConnectionAttemptFailedException(val endpoint: InetSocketAddress) extends ConnectionException(s"Connection attempt to $endpoint failed")

  //////////////////// EXTENSION SETUP ///////////////////

  def apply()(implicit system: ActorSystem): StreamTcpExt = super.apply(system)

  def lookup() = StreamTcp

  def createExtension(system: ExtendedActorSystem): StreamTcpExt = new StreamTcpExt(system)
}

class StreamTcpExt(system: ExtendedActorSystem) extends akka.actor.Extension {
  import StreamTcpExt._
  import StreamTcp._
  import system.dispatcher

  private val manager: ActorRef = system.systemActorOf(Props[StreamTcpManager], name = "IO-TCP-STREAM")

  /**
   * Creates a [[ServerBinding]] instance which represents a prospective TCP server binding on the given `endpoint`.
   */
  def bind(endpoint: InetSocketAddress,
           backlog: Int = 100,
           options: immutable.Traversable[SocketOption] = Nil,
           idleTimeout: Duration = Duration.Inf): ServerBinding = {
    val connectionSource = new KeyedActorFlowSource[IncomingConnection, (Future[InetSocketAddress], Future[() ⇒ Future[Unit]])] {
      override def attach(flowSubscriber: Subscriber[IncomingConnection],
                          materializer: ActorBasedFlowMaterializer,
                          flowName: String): MaterializedType = {
        val localAddressPromise = Promise[InetSocketAddress]()
        val unbindPromise = Promise[() ⇒ Future[Unit]]()
        manager ! StreamTcpManager.Bind(localAddressPromise, unbindPromise, flowSubscriber, endpoint, backlog, options,
          idleTimeout)
        localAddressPromise.future -> unbindPromise.future
      }
    }
    new ServerBinding {
      def localAddress(mm: MaterializedMap) = mm.get(connectionSource)._1
      def connections = connectionSource
      def unbind(mm: MaterializedMap): Future[Unit] = mm.get(connectionSource)._2.flatMap(_())
    }
  }

  /**
   * Creates an [[OutgoingConnection]] instance representing a prospective TCP client connection to the given endpoint.
   */
  def outgoingConnection(remoteAddress: InetSocketAddress,
                         localAddress: Option[InetSocketAddress] = None,
                         options: immutable.Traversable[SocketOption] = Nil,
                         connectTimeout: Duration = Duration.Inf,
                         idleTimeout: Duration = Duration.Inf): OutgoingConnection = {
    val remoteAddr = remoteAddress
    val key = new PreMaterializedOutgoingKey()
    val stream = Pipe(key) { () ⇒
      val processorPromise = Promise[Processor[ByteString, ByteString]]()
      val localAddressPromise = Promise[InetSocketAddress]()
      manager ! StreamTcpManager.Connect(processorPromise, localAddressPromise, remoteAddress, localAddress, options,
        connectTimeout, idleTimeout)
      (new DelayedInitProcessor[ByteString, ByteString](processorPromise.future), localAddressPromise.future)
    }
    new OutgoingConnection {
      def remoteAddress = remoteAddr
      def localAddress(mm: MaterializedMap) = mm.get(key)
      def flow = stream
      def handleWith(handler: Flow[ByteString, ByteString])(implicit fm: FlowMaterializer) =
        flow.join(handler).run()
    }
  }
}

/**
 * INTERNAL API
 */
private[akka] object StreamTcpExt {
  /**
   * INTERNAL API
   */
  class PreMaterializedOutgoingKey extends Key[Future[InetSocketAddress]] {
    override def materialize(map: MaterializedMap) =
      throw new IllegalStateException("This key has already been materialized by the TCP Processor")
  }
}

/**
 * INTERNAL API
 */
private[akka] class DelayedInitProcessor[I, O](val implFuture: Future[Processor[I, O]])(implicit ec: ExecutionContext) extends Processor[I, O] {
  @volatile private var impl: Processor[I, O] = _
  private val setVarFuture = implFuture.andThen { case Success(p) ⇒ impl = p }

  override def onSubscribe(s: Subscription): Unit = implFuture.onComplete {
    case Success(x) ⇒ x.onSubscribe(s)
    case Failure(_) ⇒ s.cancel()
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
    case Success(x) ⇒ x.subscribe(s)
    case Failure(e) ⇒ s.onError(e)
  }
}

/**
 * INTERNAL API
 */
private[io] object StreamTcpManager {
  /**
   * INTERNAL API
   */
  private[io] case class Connect(processorPromise: Promise[Processor[ByteString, ByteString]],
                                 localAddressPromise: Promise[InetSocketAddress],
                                 remoteAddress: InetSocketAddress,
                                 localAddress: Option[InetSocketAddress],
                                 options: immutable.Traversable[SocketOption],
                                 connectTimeout: Duration,
                                 idleTimeout: Duration)

  /**
   * INTERNAL API
   */
  private[io] case class Bind(localAddressPromise: Promise[InetSocketAddress],
                              unbindPromise: Promise[() ⇒ Future[Unit]],
                              flowSubscriber: Subscriber[StreamTcp.IncomingConnection],
                              endpoint: InetSocketAddress,
                              backlog: Int,
                              options: immutable.Traversable[SocketOption],
                              idleTimeout: Duration)

  /**
   * INTERNAL API
   */
  private[io] case class ExposedProcessor(processor: Processor[ByteString, ByteString])

}

/**
 * INTERNAL API
 */
private[akka] class StreamTcpManager extends Actor {
  import akka.stream.io.StreamTcpManager._

  var nameCounter = 0
  def encName(prefix: String, endpoint: InetSocketAddress) = {
    nameCounter += 1
    s"$prefix-$nameCounter-${URLEncoder.encode(endpoint.toString, "utf-8")}"
  }

  def receive: Receive = {
    case Connect(processorPromise, localAddressPromise, remoteAddress, localAddress, options, connectTimeout, _) ⇒
      val connTimeout = connectTimeout match {
        case x: FiniteDuration ⇒ Some(x)
        case _                 ⇒ None
      }
      val processorActor = context.actorOf(TcpStreamActor.outboundProps(processorPromise, localAddressPromise,
        Tcp.Connect(remoteAddress, localAddress, options, connTimeout, pullMode = true),
        materializerSettings = MaterializerSettings(context.system)), name = encName("client", remoteAddress))
      processorActor ! ExposedProcessor(ActorProcessor[ByteString, ByteString](processorActor))

    case Bind(localAddressPromise, unbindPromise, flowSubscriber, endpoint, backlog, options, _) ⇒
      val publisherActor = context.actorOf(TcpListenStreamActor.props(localAddressPromise, unbindPromise,
        flowSubscriber, Tcp.Bind(context.system.deadLetters, endpoint, backlog, options, pullMode = true),
        MaterializerSettings(context.system)), name = encName("server", endpoint))
      // this sends the ExposedPublisher message to the publisher actor automatically
      ActorPublisher[Any](publisherActor)
  }
}
