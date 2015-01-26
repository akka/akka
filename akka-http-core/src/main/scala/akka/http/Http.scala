/**
 * Copyright (C) 2009-2014 Typesafe Inc. <http://www.typesafe.com>
 */

package akka.http

import java.net.InetSocketAddress
import com.typesafe.config.Config
import scala.collection.immutable
import scala.concurrent.Future
import akka.event.LoggingAdapter
import akka.util.ByteString
import akka.io.Inet
import akka.stream.FlowMaterializer
import akka.stream.scaladsl._
import akka.http.engine.client.{ HttpClient, ClientConnectionSettings }
import akka.http.engine.server.{ HttpServer, ServerSettings }
import akka.http.model.{ ErrorInfo, HttpResponse, HttpRequest }
import akka.actor._

class HttpExt(config: Config)(implicit system: ActorSystem) extends akka.actor.Extension {
  import Http._

  /**
   * Creates a [[ServerBinding]] instance which represents a prospective HTTP server binding on the given `endpoint`.
   */
  def bind(interface: String, port: Int = 80, backlog: Int = 100,
           options: immutable.Traversable[Inet.SocketOption] = Nil,
           settings: Option[ServerSettings] = None,
           log: LoggingAdapter = system.log): ServerBinding = {
    val endpoint = new InetSocketAddress(interface, port)
    val effectiveSettings = ServerSettings(settings)
    val tcpBinding = StreamTcp().bind(endpoint, backlog, options, effectiveSettings.timeouts.idleTimeout)
    new ServerBinding {
      def localAddress(mm: MaterializedMap): Future[InetSocketAddress] = tcpBinding.localAddress(mm)
      val connections = tcpBinding.connections map { tcpConn ⇒
        new IncomingConnection {
          def localAddress = tcpConn.localAddress
          def remoteAddress = tcpConn.remoteAddress
          def handleWith(handler: Flow[HttpRequest, HttpResponse])(implicit fm: FlowMaterializer) =
            tcpConn.handleWith(HttpServer.serverFlowToTransport(handler, effectiveSettings, log))
        }
      }
      def unbind(mm: MaterializedMap): Future[Unit] = tcpBinding.unbind(mm)
    }
  }

  /**
   * Transforms a given HTTP-level server [[Flow]] into a lower-level TCP transport flow.
   */
  def serverFlowToTransport(serverFlow: Flow[HttpRequest, HttpResponse],
                            settings: Option[ServerSettings] = None,
                            log: LoggingAdapter = system.log)(implicit mat: FlowMaterializer): Flow[ByteString, ByteString] = {
    val effectiveSettings = ServerSettings(settings)
    HttpServer.serverFlowToTransport(serverFlow, effectiveSettings, log)
  }

  /**
   * Creates an [[OutgoingConnection]] instance representing a prospective HTTP client connection to the given endpoint.
   */
  def outgoingConnection(host: String, port: Int = 80,
                         localAddress: Option[InetSocketAddress] = None,
                         options: immutable.Traversable[Inet.SocketOption] = Nil,
                         settings: Option[ClientConnectionSettings] = None,
                         log: LoggingAdapter = system.log): OutgoingConnection = {
    val effectiveSettings = ClientConnectionSettings(settings)
    val remoteAddr = new InetSocketAddress(host, port)
    val transportFlow = StreamTcp().outgoingConnection(remoteAddr, localAddress,
      options, effectiveSettings.connectingTimeout, effectiveSettings.idleTimeout)
    new OutgoingConnection {
      def remoteAddress = remoteAddr
      def localAddress(mm: MaterializedMap) = transportFlow.localAddress(mm)
      val flow = HttpClient.transportToConnectionClientFlow(transportFlow.flow, remoteAddr, effectiveSettings, log)
    }
  }

  /**
   * Transforms the given low-level TCP client transport [[Flow]] into a higher-level HTTP client flow.
   */
  def transportToConnectionClientFlow(transport: Flow[ByteString, ByteString],
                                      remoteAddress: InetSocketAddress, // TODO: removed after #16168 is cleared
                                      settings: Option[ClientConnectionSettings] = None,
                                      log: LoggingAdapter = system.log): Flow[HttpRequest, HttpResponse] = {
    val effectiveSettings = ClientConnectionSettings(settings)
    HttpClient.transportToConnectionClientFlow(transport, remoteAddress, effectiveSettings, log)
  }
}

object Http extends ExtensionId[HttpExt] with ExtensionIdProvider {

  /**
   * Represents a prospective HTTP server binding.
   */
  sealed trait ServerBinding {
    /**
     * The local address of the endpoint bound by the materialization of the `connections` [[Source]]
     * whose [[MaterializedMap]] is passed as parameter.
     */
    def localAddress(materializedMap: MaterializedMap): Future[InetSocketAddress]

    /**
     * The stream of accepted incoming connections.
     * Can be materialized several times but only one subscription can be "live" at one time, i.e.
     * subsequent materializations will reject subscriptions with an [[StreamTcp.BindFailedException]] if the previous
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

    /**
     * Materializes the `connections` [[Source]] and handles all connections with the given flow.
     *
     * Note that there is no backpressure being applied to the `connections` [[Source]], i.e. all
     * connections are being accepted at maximum rate, which, depending on the applications, might
     * present a DoS risk!
     */
    def startHandlingWith(handler: Flow[HttpRequest, HttpResponse])(implicit fm: FlowMaterializer): MaterializedMap =
      connections.to(ForeachSink(_ handleWith handler)).run()

    /**
     * Materializes the `connections` [[Source]] and handles all connections with the given flow.
     *
     * Note that there is no backpressure being applied to the `connections` [[Source]], i.e. all
     * connections are being accepted at maximum rate, which, depending on the applications, might
     * present a DoS risk!
     */
    def startHandlingWithSyncHandler(handler: HttpRequest ⇒ HttpResponse)(implicit fm: FlowMaterializer): MaterializedMap =
      startHandlingWith(Flow[HttpRequest].map(handler))

    /**
     * Materializes the `connections` [[Source]] and handles all connections with the given flow.
     *
     * Note that there is no backpressure being applied to the `connections` [[Source]], i.e. all
     * connections are being accepted at maximum rate, which, depending on the applications, might
     * present a DoS risk!
     */
    def startHandlingWithAsyncHandler(handler: HttpRequest ⇒ Future[HttpResponse])(implicit fm: FlowMaterializer): MaterializedMap =
      startHandlingWith(Flow[HttpRequest].mapAsync(handler))
  }

  /**
   * Represents one accepted incoming HTTP connection.
   */
  sealed trait IncomingConnection {
    /**
     * The local address this connection is bound to.
     */
    def localAddress: InetSocketAddress

    /**
     * The remote address this connection is bound to.
     */
    def remoteAddress: InetSocketAddress

    /**
     * Handles the connection with the given flow, which is materialized exactly once
     * and the respective [[MaterializedMap]] returned.
     */
    def handleWith(handler: Flow[HttpRequest, HttpResponse])(implicit fm: FlowMaterializer): MaterializedMap

    /**
     * Handles the connection with the given handler function.
     * Returns the [[MaterializedMap]] of the underlying flow materialization.
     */
    def handleWithSyncHandler(handler: HttpRequest ⇒ HttpResponse)(implicit fm: FlowMaterializer): MaterializedMap =
      handleWith(Flow[HttpRequest].map(handler))

    /**
     * Handles the connection with the given handler function.
     * Returns the [[MaterializedMap]] of the underlying flow materialization.
     */
    def handleWithAsyncHandler(handler: HttpRequest ⇒ Future[HttpResponse])(implicit fm: FlowMaterializer): MaterializedMap =
      handleWith(Flow[HttpRequest].mapAsync(handler))
  }

  /**
   * Represents a prospective outgoing HTTP connection.
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
     * A flow representing the HTTP server on a single HTTP connection.
     * This flow can be materialized several times, every materialization will open a new connection to the `remoteAddress`.
     * If the connection cannot be established the materialized stream will immediately be terminated
     * with a [[akka.stream.StreamTcpException]].
     */
    def flow: Flow[HttpRequest, HttpResponse]
  }

  //////////////////// EXTENSION SETUP ///////////////////

  def apply()(implicit system: ActorSystem): HttpExt = super.apply(system)

  def lookup() = Http

  def createExtension(system: ExtendedActorSystem): HttpExt =
    new HttpExt(system.settings.config getConfig "akka.http")(system)
}
