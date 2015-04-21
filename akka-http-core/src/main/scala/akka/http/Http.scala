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
import akka.http.model.{ HttpResponse, HttpRequest }
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

    connections.map {
      case StreamTcp.IncomingConnection(localAddress, remoteAddress, flow) ⇒
        val layer = serverLayer(effectiveSettings, log)
        IncomingConnection(localAddress, remoteAddress, layer join flow)
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
                    log: LoggingAdapter = system.log)(implicit fm: FlowMaterializer): Future[ServerBinding] =
    bind(interface, port, backlog, options, settings, log).to {
      Sink.foreach { _.flow.join(handler).run() }
    }.run()

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
   * The type of the server-side HTTP layer as a stand-alone BidiStage
   * that can be put atop the TCP layer to form an HTTP server.
   *
   * {{{
   *                +------+
   * HttpResponse ~>|      |~> ByteString
   *                | bidi |
   * HttpRequest  <~|      |<~ ByteString
   *                +------+
   * }}}
   */
  type ServerLayer = BidiFlow[HttpResponse, ByteString, ByteString, HttpRequest, Unit]

  /**
   * Constructs a [[ServerLayer]] stage using the configured default [[ServerSettings]].
   */
  def serverLayer()(implicit mat: FlowMaterializer): ServerLayer = serverLayer(ServerSettings(system))

  /**
   * Constructs a [[ServerLayer]] stage using the given [[ServerSettings]].
   */
  def serverLayer(settings: ServerSettings,
                  log: LoggingAdapter = system.log)(implicit mat: FlowMaterializer): ServerLayer =
    BidiFlow.wrap(HttpServerBluePrint(settings, log))

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
    val layer = clientLayer(remoteAddr, effectiveSettings, log)

    val transportFlow = StreamTcp().outgoingConnection(remoteAddr, localAddress,
      options, effectiveSettings.connectingTimeout, effectiveSettings.idleTimeout)

    layer.joinMat(transportFlow) { (_, tcpConnFuture) ⇒
      import system.dispatcher
      tcpConnFuture map { tcpConn ⇒ OutgoingConnection(tcpConn.localAddress, tcpConn.remoteAddress) }
    }
  }

  /**
   * The type of the client-side HTTP layer as a stand-alone BidiStage
   * that can be put atop the TCP layer to form an HTTP client.
   *
   * {{{
   *                +------+
   * HttpRequest  ~>|      |~> ByteString
   *                | bidi |
   * HttpResponse <~|      |<~ ByteString
   *                +------+
   * }}}
   */
  type ClientLayer = BidiFlow[HttpRequest, ByteString, ByteString, HttpResponse, Unit]

  /**
   * Constructs a [[ClientLayer]] stage using the configured default [[ClientConnectionSettings]].
   */
  def clientLayer(remoteAddress: InetSocketAddress /* TODO: remove after #16168 is cleared */ ): ClientLayer =
    clientLayer(remoteAddress, ClientConnectionSettings(system))

  /**
   * Constructs a [[ClientLayer]] stage using the given [[ClientConnectionSettings]].
   */
  def clientLayer(remoteAddress: InetSocketAddress, // TODO: remove after #16168 is cleared
                  settings: ClientConnectionSettings,
                  log: LoggingAdapter = system.log): ClientLayer =
    BidiFlow.wrap(OutgoingConnectionBlueprint(remoteAddress, settings, log))
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
