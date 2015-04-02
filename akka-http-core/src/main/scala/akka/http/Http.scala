/**
 * Copyright (C) 2009-2014 Typesafe Inc. <http://www.typesafe.com>
 */

package akka.http

import java.net.InetSocketAddress
import java.util.concurrent.ConcurrentHashMap
import akka.http.util.FastFuture
import com.typesafe.config.Config
import scala.annotation.tailrec
import scala.util.control.NonFatal
import scala.util.Try
import scala.collection.immutable
import scala.concurrent.{ Promise, Future }
import akka.event.LoggingAdapter
import akka.util.ByteString
import akka.io.Inet
import akka.stream.FlowMaterializer
import akka.stream.scaladsl._
import akka.http.engine.client._
import akka.http.engine.server._
import akka.http.model._
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
    val layer = serverLayer(effectiveSettings, log)

    connections.map {
      case StreamTcp.IncomingConnection(localAddress, remoteAddress, flow) ⇒
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

  /**
   * Starts a new connection pool to the given host and configuration and returns a [[Flow]] which dispatches
   * the requests from all its materializations across this pool.
   * While the started host connection pool internally shuts itself down automatically after the configured idle
   * timeout it will spin itself up again if more requests arrive from an existing or a new client flow
   * materialization. The returned flow therefore remains usable for the full lifetime of the application.
   *
   * Since the underlying transport usually comprises more than a single connection the produced flow might generate
   * responses in an order that doesn't directly match the consumed requests.
   * For example, if two requests A and B enter the flow in that order the response for B might be produced before the
   * response for A.
   * In order to allow for easy response-to-request association the flow takes in a custom, opaque context
   * object of type ``T`` from the application which is emitted together with the corresponding response.
   */
  def newHostConnectionPool[T](host: String, port: Int = 80,
                               options: immutable.Traversable[Inet.SocketOption] = Nil,
                               settings: Option[HostConnectionPoolSettings] = None,
                               defaultHeaders: List[HttpHeader] = Nil,
                               log: LoggingAdapter = system.log)(implicit fm: FlowMaterializer): Flow[(HttpRequest, T), (Try[HttpResponse], T), HostConnectionPool] = {
    val effectiveSettings = HostConnectionPoolSettings(settings)
    val cps = ConnectionPoolSetup(encrypted = false, options, effectiveSettings, defaultHeaders, log)
    val setup = HostConnectionPoolSetup(host, port, cps)
    newHostConnectionPool(setup)
  }

  /**
   * Starts a new connection pool to the given host and configuration and returns a [[Flow]] which dispatches
   * the requests from all its materializations across this pool.
   * While the started host connection pool internally shuts itself down automatically after the configured idle
   * timeout it will spin itself up again if more requests arrive from an existing or a new client flow
   * materialization. The returned flow therefore remains usable for the full lifetime of the application.
   *
   * Since the underlying transport usually comprises more than a single connection the produced flow might generate
   * responses in an order that doesn't directly match the consumed requests.
   * For example, if two requests A and B enter the flow in that order the response for B might be produced before the
   * response for A.
   * In order to allow for easy response-to-request association the flow takes in a custom, opaque context
   * object of type ``T`` from the application which is emitted together with the corresponding response.
   */
  def newHostConnectionPool[T](setup: HostConnectionPoolSetup)(
    implicit fm: FlowMaterializer): Flow[(HttpRequest, T), (Try[HttpResponse], T), HostConnectionPool] = {
    val gateway = ConnectionPool.startHostConnectionPool(setup)
    clientFlow[T]((_, _) ⇒ gateway).mapMaterialized(_ ⇒ HostConnectionPool(setup)(gateway))
  }

  /**
   * Returns a [[Flow]] which dispatches incoming HTTP requests to the per-ActorSystem pool of outgoing
   * HTTP connections to the given target host endpoint. For every ActorSystem, target host and pool
   * configuration a separate connection pool is maintained.
   * The HTTP layer transparently manages idle shutdown and restarting of connections pools as configured.
   * The returned [[Flow]] instances therefore remain valid throughout the lifetime of the application.
   *
   * Note that the provided caching logic follows a "best-effort" design with regard to pool uniqueness.
   * I cannot fully guarantee that the application will always run at most one pool for a given host and
   * configuration.
   * The cache removes entries for pools that are shut down (e.g. because of an idle timeout) and due to the
   * inherent raciness of this operation one client might end up re-starting an old pool while another
   * client starts a new pool if both access the cache around the time of the shutdown.
   *
   * Since the underlying transport usually comprises more than a single connection the produced flow might generate
   * responses in an order that doesn't directly match the consumed requests.
   * For example, if two requests A and B enter the flow in that order the response for B might be produced before the
   * response for A.
   * In order to allow for easy response-to-request association the flow takes in a custom, opaque context
   * object of type ``T`` from the application which is emitted together with the corresponding response.
   */
  def cachedHostConnectionPool[T](host: String, port: Int = 80,
                                  options: immutable.Traversable[Inet.SocketOption] = Nil,
                                  settings: Option[HostConnectionPoolSettings] = None,
                                  defaultHeaders: List[HttpHeader] = Nil,
                                  log: LoggingAdapter = system.log)(implicit fm: FlowMaterializer): Flow[(HttpRequest, T), (Try[HttpResponse], T), HostConnectionPool] = {
    val effectiveSettings = HostConnectionPoolSettings(settings)
    val cps = ConnectionPoolSetup(encrypted = false, options, effectiveSettings, defaultHeaders, log)
    val setup = HostConnectionPoolSetup(host, port, cps)
    cachedHostConnectionPool(setup)
  }

  // every ActorSystem maintains its own connection pools
  private[this] val hostPoolCache = new ConcurrentHashMap[HostConnectionPoolSetup, ConnectionPool.Gateway]

  /**
   * Returns a [[Flow]] which dispatches incoming HTTP requests to the per-ActorSystem pool of outgoing
   * HTTP connections to the given target host endpoint. For every ActorSystem, target host and pool
   * configuration a separate connection pool is maintained.
   * The HTTP layer transparently manages idle shutdown and restarting of connections pools as configured.
   * The returned [[Flow]] instances therefore remain valid throughout the lifetime of the application.
   *
   * Note that the provided caching logic follows a "best-effort" design with regard to pool uniqueness.
   * I cannot fully guarantee that the application will always run at most one pool for a given host and
   * configuration.
   * The cache removes entries for pools that are shut down (e.g. because of an idle timeout) and due to the
   * inherent raciness of this operation one client might end up re-starting an old pool while another
   * client starts a new pool if both access the cache around the time of the shutdown.
   *
   * Since the underlying transport usually comprises more than a single connection the produced flow might generate
   * responses in an order that doesn't directly match the consumed requests.
   * For example, if two requests A and B enter the flow in that order the response for B might be produced before the
   * response for A.
   * In order to allow for easy response-to-request association the flow takes in a custom, opaque context
   * object of type ``T`` from the application which is emitted together with the corresponding response.
   */
  def cachedHostConnectionPool[T](setup: HostConnectionPoolSetup)(implicit fm: FlowMaterializer): Flow[(HttpRequest, T), (Try[HttpResponse], T), HostConnectionPool] = {
    val gateway = cachedGateway(setup)
    clientFlow[T]((_, _) ⇒ gateway).mapMaterialized(_ ⇒ HostConnectionPool(setup)(gateway))
  }

  /**
   * Creates a new "super connection pool flow", which routes incoming requests to a (cached) host connection pool
   * depending on their respective effective URI. Note that incoming requests must have either an absolute URI or
   * a valid `Host` header.
   *
   * Since the underlying transport usually comprises more than a single connection the produced flow might generate
   * responses in an order that doesn't directly match the consumed requests.
   * For example, if two requests A and B enter the flow in that order the response for B might be produced before the
   * response for A.
   * In order to allow for easy response-to-request association the flow takes in a custom, opaque context
   * object of type ``T`` from the application which is emitted together with the corresponding response.
   */
  def superPool[T](options: immutable.Traversable[Inet.SocketOption] = Nil,
                   settings: Option[HostConnectionPoolSettings] = None,
                   defaultHeaders: List[HttpHeader] = Nil,
                   log: LoggingAdapter = system.log)(implicit fm: FlowMaterializer): Flow[(HttpRequest, T), (Try[HttpResponse], T), Any] = {
    val effectiveSettings = HostConnectionPoolSettings(settings)
    val setup = ConnectionPoolSetup(encrypted = false, options, effectiveSettings, defaultHeaders, log)
    clientFlow[T] { (request, userContext) ⇒
      val effectiveRequest = request.withEffectiveUri(securedConnection = false)
      val Uri.Authority(host, port, _) = effectiveRequest.uri.authority
      cachedGateway(HostConnectionPoolSetup(host.toString(), port, setup))
    }
  }

  /**
   * Fires a single [[HttpRequest]] across the (potentially cached) host connection pool for the request's
   * effective URI to produce a response future.
   *
   * Note that the request must have either an absolute URI or a valid `Host` header, otherwise
   * the future will be completed with an error.
   */
  def singleRequest(request: HttpRequest,
                    options: immutable.Traversable[Inet.SocketOption] = Nil,
                    settings: Option[HostConnectionPoolSettings] = None,
                    defaultHeaders: List[HttpHeader] = Nil,
                    log: LoggingAdapter = system.log)(implicit fm: FlowMaterializer): Future[HttpResponse] =
    try {
      val effectiveSettings = HostConnectionPoolSettings(settings)
      val setup = ConnectionPoolSetup(encrypted = false, options, effectiveSettings, defaultHeaders, log)
      val effectiveRequest = request.withEffectiveUri(securedConnection = false)
      val uri = effectiveRequest.uri
      val gateway = cachedGateway(HostConnectionPoolSetup(uri.authority.host.toString(), uri.effectivePort, setup))
      val responsePromise = Promise[HttpResponse]()
      gateway.actorRef() ! ConnectionPool.PoolRequest(request, responsePromise)
      responsePromise.future
    } catch {
      case e: IllegalUriException ⇒ FastFuture.failed(e)
    }

  /**
   * Triggers an orderly shutdown of all host connections pools currently maintained by the [[ActorSystem]].
   * The returned future is completed when all pools that were live at the time of this method call
   * have completed their shutdown process.
   *
   * If existing pool client flows are re-used or new ones materialized concurrently with or after this
   * method call the respective connection pools will be restarted and not contribute to the returned future.
   */
  def shutdownAllConnectionPools(): Future[Unit] = {
    import scala.collection.JavaConverters._
    import system.dispatcher
    val gateways = hostPoolCache.values().asScala
    system.log.info("Initiating orderly shutdown of all active host connections pools (w/ configured grace period)...")
    Future.sequence(gateways.map(_.shutdown())).map(_ ⇒ ())
  }

  @tailrec
  private def cachedGateway(setup: HostConnectionPoolSetup)(implicit fm: FlowMaterializer): ConnectionPool.Gateway =
    hostPoolCache.putIfAbsent(setup, ConnectionPool.CowboyGateway) match {
      case null ⇒ // we are the first to attempt starting a pool for this setup
        try {
          val gateway = ConnectionPool.startHostConnectionPool(setup)
          hostPoolCache.put(setup, gateway)
          gateway.whenShuttingDown.onComplete(_ ⇒ hostPoolCache.remove(setup))(system.dispatcher)
          gateway
        } catch {
          case NonFatal(e) ⇒
            hostPoolCache.remove(setup) // remove Cowboy entry if we weren't able to properly start the pool
            throw e
        }

      case ConnectionPool.CowboyGateway ⇒ cachedGateway(setup) // spin while other thread is starting a pool for this setup
      case pool                         ⇒ pool // return cached instance
    }

  private def clientFlow[T](poolGateway: (HttpRequest, Any) ⇒ ConnectionPool.Gateway)(
    implicit system: ActorSystem, fm: FlowMaterializer): Flow[(HttpRequest, T), (Try[HttpResponse], T), Any] =
    Flow[(HttpRequest, T)].mapAsync(parallelism = 4, {
      case (request, userContext) ⇒
        val responsePromise = Promise[HttpResponse]()
        poolGateway(request, userContext).actorRef() ! ConnectionPool.PoolRequest(request, responsePromise)
        val result = Promise[(Try[HttpResponse], T)]()
        responsePromise.future.onComplete(responseTry ⇒ result.success(responseTry -> userContext))(system.dispatcher)
        result.future
    })
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

  /**
   * Represents a connection pool to a specific target host and pool configuration.
   */
  case class HostConnectionPool(setup: HostConnectionPoolSetup)(private val gateway: ConnectionPool.Gateway) {

    /**
     * Asynchronously triggers the shutdown of the host connection pool.
     *
     * The produced [[Future]] is fulfilled when the shutdown has been completed.
     */
    def shutdown(): Future[Unit] = gateway.shutdown()
  }

  //////////////////// EXTENSION SETUP ///////////////////

  def apply()(implicit system: ActorSystem): HttpExt = super.apply(system)

  def lookup() = Http

  def createExtension(system: ExtendedActorSystem): HttpExt =
    new HttpExt(system.settings.config getConfig "akka.http")(system)
}
