/**
 * Copyright (C) 2009-2014 Typesafe Inc. <http://www.typesafe.com>
 */
package akka.stream.scaladsl

import java.net.{ InetSocketAddress, URLEncoder }
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
import akka.stream.FlowMaterializer
import akka.stream.ActorFlowMaterializer
import akka.stream.impl._
import akka.stream.scaladsl._
import akka.util.ByteString
import org.reactivestreams.{ Processor, Subscriber, Subscription }
import akka.stream.impl.io.TcpStreamActor
import akka.stream.impl.io.TcpListenStreamActor
import akka.stream.impl.io.DelayedInitProcessor
import akka.stream.impl.io.StreamTcpManager

object StreamTcp extends ExtensionId[StreamTcp] with ExtensionIdProvider {

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
     * The produced [[scala.concurrent.Future]] is fulfilled when the unbinding has been completed.
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
  trait OutgoingConnection {
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
     * with a [[akka.stream.StreamTcpException]].
     *
     * Convenience shortcut for: `flow.join(handler).run()`.
     */
    def handleWith(handler: Flow[ByteString, ByteString])(implicit materializer: FlowMaterializer): MaterializedMap

    /**
     * A flow representing the server on the other side of the connection.
     * This flow can be materialized several times, every materialization will open a new connection to the
     * `remoteAddress`. If the connection cannot be established the materialized stream will immediately be terminated
     * with a [[akka.stream.StreamTcpException]].
     */
    def flow: Flow[ByteString, ByteString]
  }

  /**
   * INTERNAL API
   */
  private[akka] class PreMaterializedOutgoingKey extends Key[Future[InetSocketAddress]] {
    override def materialize(map: MaterializedMap) =
      throw new IllegalStateException("This key has already been materialized by the TCP Processor")
  }

  def apply()(implicit system: ActorSystem): StreamTcp = super.apply(system)

  override def get(system: ActorSystem): StreamTcp = super.get(system)

  def lookup() = StreamTcp

  def createExtension(system: ExtendedActorSystem): StreamTcp = new StreamTcp(system)
}

class StreamTcp(system: ExtendedActorSystem) extends akka.actor.Extension {
  import StreamTcp._
  import system.dispatcher

  private val manager: ActorRef = system.systemActorOf(Props[StreamTcpManager], name = "IO-TCP-STREAM")

  /**
   * Creates a [[StreamTcp.ServerBinding]] instance which represents a prospective TCP server binding on the given `endpoint`.
   */
  def bind(endpoint: InetSocketAddress,
           backlog: Int = 100,
           options: immutable.Traversable[SocketOption] = Nil,
           idleTimeout: Duration = Duration.Inf): ServerBinding = {
    val connectionSource = new KeyedActorFlowSource[IncomingConnection, (Future[InetSocketAddress], Future[() ⇒ Future[Unit]])] {
      override def attach(flowSubscriber: Subscriber[IncomingConnection],
                          materializer: ActorFlowMaterializer,
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
   * Creates an [[StreamTcp.OutgoingConnection]] instance representing a prospective TCP client connection to the given endpoint.
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

