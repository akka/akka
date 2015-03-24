/**
 * Copyright (C) 2009-2014 Typesafe Inc. <http://www.typesafe.com>
 */

package akka.http

import java.net.InetSocketAddress
import akka.http.engine.server.HttpServer.HttpServerPorts
import akka.stream.Graph
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
import akka.http.model.{ HttpResponse, HttpRequest }
import akka.actor._

class HttpExt(config: Config)(implicit system: ActorSystem) extends akka.actor.Extension {
  import Http._

  /**
   * Creates a [[ServerBinding]] instance which represents a prospective HTTP server binding on the given `endpoint`.
   */
  def bind(interface: String, port: Int = 80, backlog: Int = 100,
           options: immutable.Traversable[Inet.SocketOption] = Nil,
           settings: Option[ServerSettings] = None,
           log: LoggingAdapter = system.log)(implicit fm: FlowMaterializer): Source[IncomingConnection, Future[ServerBinding]] = {
    val endpoint = new InetSocketAddress(interface, port)
    val effectiveSettings = ServerSettings(settings)

    val connections: Source[StreamTcp.IncomingConnection, Future[StreamTcp.ServerBinding]] = StreamTcp().bind(endpoint, backlog, options, effectiveSettings.timeouts.idleTimeout)
    val serverBlueprint: Graph[HttpServerPorts, Unit] = HttpServer.serverBlueprint(effectiveSettings, log)

    connections.map { conn ⇒
      val flow = Flow(conn.flow, serverBlueprint)(Keep.right) { implicit b ⇒
        (tcp, http) ⇒
          import FlowGraph.Implicits._
          tcp.outlet ~> http.bytesIn
          http.bytesOut ~> tcp.inlet
          (http.httpResponses, http.httpRequests)
      }

      IncomingConnection(conn.localAddress, conn.remoteAddress, flow)
    }.mapMaterialized { tcpBindingFuture ⇒
      import system.dispatcher
      tcpBindingFuture.map { tcpBinding ⇒ ServerBinding(tcpBinding.localAddress)(() ⇒ tcpBinding.unbind()) }
    }

  }

  /**
   * Materializes the `connections` [[Source]] and handles all connections with the given flow.
   *
   * Note that there is no backpressure being applied to the `connections` [[Source]], i.e. all
   * connections are being accepted at maximum rate, which, depending on the applications, might
   * present a DoS risk!
   */
  def bindAndStartHandlingWith(handler: Flow[HttpRequest, HttpResponse, _],
                               interface: String, port: Int = 80, backlog: Int = 100,
                               options: immutable.Traversable[Inet.SocketOption] = Nil,
                               settings: Option[ServerSettings] = None,
                               log: LoggingAdapter = system.log)(implicit fm: FlowMaterializer): Future[ServerBinding] = {
    bind(interface, port, backlog, options, settings, log).toMat(Sink.foreach { conn ⇒
      conn.flow.join(handler).run()
    })(Keep.left).run()
  }

  /**
   * Materializes the `connections` [[Source]] and handles all connections with the given flow.
   *
   * Note that there is no backpressure being applied to the `connections` [[Source]], i.e. all
   * connections are being accepted at maximum rate, which, depending on the applications, might
   * present a DoS risk!
   */
  def bindAndStartHandlingWithSyncHandler(handler: HttpRequest ⇒ HttpResponse,
                                          interface: String, port: Int = 80, backlog: Int = 100,
                                          options: immutable.Traversable[Inet.SocketOption] = Nil,
                                          settings: Option[ServerSettings] = None,
                                          log: LoggingAdapter = system.log)(implicit fm: FlowMaterializer): Future[ServerBinding] =
    bindAndStartHandlingWith(Flow[HttpRequest].map(handler), interface, port, backlog, options, settings, log)

  /**
   * Materializes the `connections` [[Source]] and handles all connections with the given flow.
   *
   * Note that there is no backpressure being applied to the `connections` [[Source]], i.e. all
   * connections are being accepted at maximum rate, which, depending on the applications, might
   * present a DoS risk!
   */
  def startHandlingWithAsyncHandler(handler: HttpRequest ⇒ Future[HttpResponse],
                                    interface: String, port: Int = 80, backlog: Int = 100,
                                    options: immutable.Traversable[Inet.SocketOption] = Nil,
                                    settings: Option[ServerSettings] = None,
                                    log: LoggingAdapter = system.log)(implicit fm: FlowMaterializer): Future[ServerBinding] =
    bindAndStartHandlingWith(Flow[HttpRequest].mapAsync(handler), interface, port, backlog, options, settings, log)

  /**
   * Transforms a given HTTP-level server [[Flow]] into a lower-level TCP transport flow.
   */
  def serverFlowToTransport[Mat](serverFlow: Flow[HttpRequest, HttpResponse, Mat],
                                 settings: Option[ServerSettings] = None,
                                 log: LoggingAdapter = system.log)(implicit mat: FlowMaterializer): Flow[ByteString, ByteString, Mat] = {
    val effectiveSettings = ServerSettings(settings)
    val serverBlueprint: Graph[HttpServerPorts, Unit] = HttpServer.serverBlueprint(effectiveSettings, log)

    Flow(serverBlueprint, serverFlow)(Keep.right) { implicit b ⇒
      (server, user) ⇒
        import FlowGraph.Implicits._
        server.httpRequests ~> user.inlet
        user.outlet ~> server.httpResponses

        (server.bytesIn, server.bytesOut)
    }

  }

  /**
   * Creates an [[OutgoingConnection]] instance representing a prospective HTTP client connection to the given endpoint.
   */
  def outgoingConnection(host: String, port: Int = 80,
                         localAddress: Option[InetSocketAddress] = None,
                         options: immutable.Traversable[Inet.SocketOption] = Nil,
                         settings: Option[ClientConnectionSettings] = None,
                         log: LoggingAdapter = system.log): Flow[HttpRequest, HttpResponse, Future[OutgoingConnection]] = {
    val effectiveSettings = ClientConnectionSettings(settings)
    val remoteAddr = new InetSocketAddress(host, port)
    val transportFlow = StreamTcp().outgoingConnection(remoteAddr, localAddress,
      options, effectiveSettings.connectingTimeout, effectiveSettings.idleTimeout)
    val clientBluePrint = HttpClient.clientBlueprint(remoteAddr, effectiveSettings, log)

    Flow(transportFlow, clientBluePrint)(Keep.left) { implicit b ⇒
      (tcp, client) ⇒
        import FlowGraph.Implicits._

        tcp.outlet ~> client.bytesIn
        client.bytesOut ~> tcp.inlet

        (client.httpRequests, client.httpResponses)
    }.mapMaterialized { tcpConnFuture ⇒
      import system.dispatcher
      tcpConnFuture.map { tcpConn ⇒ OutgoingConnection(tcpConn.localAddress, tcpConn.remoteAddress) }
    }

  }

  /**
   * Transforms the given low-level TCP client transport [[Flow]] into a higher-level HTTP client flow.
   */
  def transportToConnectionClientFlow[Mat](transport: Flow[ByteString, ByteString, Mat],
                                           remoteAddress: InetSocketAddress, // TODO: removed after #16168 is cleared
                                           settings: Option[ClientConnectionSettings] = None,
                                           log: LoggingAdapter = system.log): Flow[HttpRequest, HttpResponse, Mat] = {
    val effectiveSettings = ClientConnectionSettings(settings)
    val clientBlueprint = HttpClient.clientBlueprint(remoteAddress, effectiveSettings, log)

    Flow(clientBlueprint, transport)(Keep.right) { implicit b ⇒
      (client, tcp) ⇒
        import FlowGraph.Implicits._
        client.bytesOut ~> tcp.inlet
        tcp.outlet ~> client.bytesIn

        (client.httpRequests, client.httpResponses)
    }
  }
}

object Http extends ExtensionId[HttpExt] with ExtensionIdProvider {

  /**
   * Represents a prospective HTTP server binding.
   *
   * @param localAddress  The local address of the endpoint bound by the materialization of the `connections` [[Source]]
   *
   */
  case class ServerBinding(localAddress: InetSocketAddress)(private val unbindAction: () ⇒ Future[Unit]) {

    /**
     * Asynchronously triggers the unbinding of the port that was bound by the materialization of the `connections`
     * [[Source]]
     *
     * The produced [[Future]] is fulfilled when the unbinding has been completed.
     */
    def unbind(): Future[Unit] = unbindAction()

  }

  /**
   * Represents one accepted incoming HTTP connection.
   */
  case class IncomingConnection(
    localAddress: InetSocketAddress,
    remoteAddress: InetSocketAddress,
    flow: Flow[HttpResponse, HttpRequest, Unit]) {

    /**
     * Handles the connection with the given flow, which is materialized exactly once
     * and the respective materialization result returned.
     */
    def handleWith[Mat](handler: Flow[HttpRequest, HttpResponse, Mat])(implicit fm: FlowMaterializer): Mat =
      flow.joinMat(handler)(Keep.right).run()

    /**
     * Handles the connection with the given handler function.
     * Returns the materialization result of the underlying flow materialization.
     */
    def handleWithSyncHandler(handler: HttpRequest ⇒ HttpResponse)(implicit fm: FlowMaterializer): Unit =
      handleWith(Flow[HttpRequest].map(handler))

    /**
     * Handles the connection with the given handler function.
     * Returns the materialization result of the underlying flow materialization.
     */
    def handleWithAsyncHandler(handler: HttpRequest ⇒ Future[HttpResponse])(implicit fm: FlowMaterializer): Unit =
      handleWith(Flow[HttpRequest].mapAsync(handler))
  }

  /**
   * Represents a prospective outgoing HTTP connection.
   */
  case class OutgoingConnection(localAddress: InetSocketAddress, remoteAddress: InetSocketAddress) {

  }

  //////////////////// EXTENSION SETUP ///////////////////

  def apply()(implicit system: ActorSystem): HttpExt = super.apply(system)

  def lookup() = Http

  def createExtension(system: ExtendedActorSystem): HttpExt =
    new HttpExt(system.settings.config getConfig "akka.http")(system)
}
