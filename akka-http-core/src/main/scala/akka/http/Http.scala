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
import akka.http.engine.client._
import akka.http.engine.server._
import akka.http.model.{ HttpHeader, HttpResponse, HttpRequest }
import akka.actor._

class HttpExt(config: Config)(implicit system: ActorSystem) extends akka.actor.Extension {
  import Http._

  /**
   * Creates a [[Source]] of [[IncomingConnection]] instances which represents a prospective HTTP server binding
   * on the given `endpoint`.
   * If the given port is 0 the resulting source can be materialized several times. Each materialization will
   * then be assigned a new local port by the operating system, which can then be retrieved by the materialized
   * [[ServerBinding]].
   * If the given port is non-zero subsequent materialization attempts of the produced source will immediately
   * fail, unless the first materialization has already been unbound. Unbinding can be triggered via the materialized
   * [[ServerBinding]].
   */
  def bind(interface: String, port: Int = 80, backlog: Int = 100,
           options: immutable.Traversable[Inet.SocketOption] = Nil,
           settings: Option[ServerSettings] = None,
           log: LoggingAdapter = system.log)(implicit fm: FlowMaterializer): Source[IncomingConnection, Future[ServerBinding]] = {
    val endpoint = new InetSocketAddress(interface, port)
    val effectiveSettings = ServerSettings(settings)

    val connections: Source[StreamTcp.IncomingConnection, Future[StreamTcp.ServerBinding]] =
      StreamTcp().bind(endpoint, backlog, options, effectiveSettings.timeouts.idleTimeout)
    val bluePrint = HttpServerBluePrint(effectiveSettings, log)

    connections.map { conn ⇒
      val flow = Flow(conn.flow, bluePrint)(Keep.right) { implicit b ⇒
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
   * Convenience method which starts a new HTTP server at the given endpoint and uses the given ``handler``
   * [[Flow]] for processing all incoming connections.
   *
   * Note that there is no backpressure being applied to the `connections` [[Source]], i.e. all
   * connections are being accepted at maximum rate, which, depending on the applications, might
   * present a DoS risk!
   */
  def bindAndHandle(handler: Flow[HttpRequest, HttpResponse, _],
                    interface: String, port: Int = 80, backlog: Int = 100,
                    options: immutable.Traversable[Inet.SocketOption] = Nil,
                    settings: Option[ServerSettings] = None,
                    log: LoggingAdapter = system.log)(implicit fm: FlowMaterializer): Future[ServerBinding] = {
    bind(interface, port, backlog, options, settings, log).toMat(Sink.foreach { conn ⇒
      conn.flow.join(handler).run()
    })(Keep.left).run()
  }

  /**
   * Convenience method which starts a new HTTP server at the given endpoint and uses the given ``handler``
   * [[Flow]] for processing all incoming connections.
   *
   * Note that there is no backpressure being applied to the `connections` [[Source]], i.e. all
   * connections are being accepted at maximum rate, which, depending on the applications, might
   * present a DoS risk!
   */
  def bindAndHandleSync(handler: HttpRequest ⇒ HttpResponse,
                        interface: String, port: Int = 80, backlog: Int = 100,
                        options: immutable.Traversable[Inet.SocketOption] = Nil,
                        settings: Option[ServerSettings] = None,
                        log: LoggingAdapter = system.log)(implicit fm: FlowMaterializer): Future[ServerBinding] =
    bindAndHandle(Flow[HttpRequest].map(handler), interface, port, backlog, options, settings, log)

  /**
   * Convenience method which starts a new HTTP server at the given endpoint and uses the given ``handler``
   * [[Flow]] for processing all incoming connections.
   *
   * Note that there is no backpressure being applied to the `connections` [[Source]], i.e. all
   * connections are being accepted at maximum rate, which, depending on the applications, might
   * present a DoS risk!
   */
  def bindAndHandleAsync(handler: HttpRequest ⇒ Future[HttpResponse],
                         interface: String, port: Int = 80, backlog: Int = 100,
                         options: immutable.Traversable[Inet.SocketOption] = Nil,
                         settings: Option[ServerSettings] = None,
                         log: LoggingAdapter = system.log)(implicit fm: FlowMaterializer): Future[ServerBinding] =
    bindAndHandle(Flow[HttpRequest].mapAsync(1, handler), interface, port, backlog, options, settings, log)

  /**
   * Transforms a given HTTP-level server [[Flow]] into a lower-level TCP transport flow.
   */
  def serverFlowToTransport[Mat](serverFlow: Flow[HttpRequest, HttpResponse, Mat],
                                 settings: Option[ServerSettings] = None,
                                 log: LoggingAdapter = system.log)(implicit mat: FlowMaterializer): Flow[ByteString, ByteString, Mat] = {
    val effectiveSettings = ServerSettings(settings)
    val bluePrint = HttpServerBluePrint(effectiveSettings, log)

    Flow(bluePrint, serverFlow)(Keep.right) { implicit b ⇒
      (server, user) ⇒
        import FlowGraph.Implicits._
        server.httpRequests ~> user.inlet
        user.outlet ~> server.httpResponses
        (server.bytesIn, server.bytesOut)
    }
  }

  /**
   * Creates a [[Flow]] representing a prospective HTTP client connection to the given endpoint.
   * Every materialization of the produced flow will attempt to establish a new outgoing connection.
   */
  def outgoingConnection(host: String, port: Int = 80,
                         localAddress: Option[InetSocketAddress] = None,
                         options: immutable.Traversable[Inet.SocketOption] = Nil,
                         settings: Option[ClientConnectionSettings] = None,
                         log: LoggingAdapter = system.log): Flow[HttpRequest, HttpResponse, Future[OutgoingConnection]] = {
    val effectiveSettings = ClientConnectionSettings(settings)
    val remoteAddr = new InetSocketAddress(host, port)
    val transport = StreamTcp().outgoingConnection(remoteAddr, localAddress,
      options, effectiveSettings.connectingTimeout, effectiveSettings.idleTimeout)
    transportToConnectionClientFlow(transport, remoteAddr, Some(effectiveSettings), log)
      .mapMaterialized { tcpConnFuture ⇒
        import system.dispatcher
        tcpConnFuture.map { tcpConn ⇒ OutgoingConnection(tcpConn.localAddress, tcpConn.remoteAddress) }
      }
  }

  /**
   * Transforms the given low-level TCP client transport [[Flow]] into a higher-level HTTP client flow.
   */
  def transportToConnectionClientFlow[Mat](transport: Flow[ByteString, ByteString, Mat],
                                           remoteAddress: InetSocketAddress, // TODO: remove after #16168 is cleared
                                           settings: Option[ClientConnectionSettings] = None,
                                           log: LoggingAdapter = system.log): Flow[HttpRequest, HttpResponse, Mat] = {
    val effectiveSettings = ClientConnectionSettings(settings)
    val bluePrint = OutgoingConnectionBlueprint(remoteAddress, effectiveSettings, log)

    Flow(bluePrint, transport)(Keep.right) { implicit b ⇒
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
      handleWith(Flow[HttpRequest].mapAsync(1, handler))
  }

  /**
   * Represents a prospective outgoing HTTP connection.
   */
  case class OutgoingConnection(localAddress: InetSocketAddress, remoteAddress: InetSocketAddress)

  //////////////////// EXTENSION SETUP ///////////////////

  def apply()(implicit system: ActorSystem): HttpExt = super.apply(system)

  def lookup() = Http

  def createExtension(system: ExtendedActorSystem): HttpExt =
    new HttpExt(system.settings.config getConfig "akka.http")(system)
}
