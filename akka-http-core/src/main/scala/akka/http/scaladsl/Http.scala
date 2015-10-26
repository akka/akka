/**
 * Copyright (C) 2009-2014 Typesafe Inc. <http://www.typesafe.com>
 */

package akka.http.scaladsl

import java.net.InetSocketAddress
import java.security.SecureRandom
import java.util.concurrent.ConcurrentHashMap
import java.util.{ Collection ⇒ JCollection, Random }
import javax.net.ssl.{ SSLContext, SSLParameters }

import akka.actor._
import akka.event.LoggingAdapter
import akka.http._
import akka.http.impl.engine.client._
import akka.http.impl.engine.server._
import akka.http.impl.util.{ ReadTheDocumentationException, Java6Compat, StreamUtils }
import akka.http.impl.engine.ws.WebsocketClientBlueprint
import akka.http.scaladsl.model._
import akka.http.scaladsl.model.headers.Host
import akka.http.scaladsl.model.ws.{ WebsocketUpgradeResponse, WebsocketRequest, Message }
import akka.http.scaladsl.util.FastFuture
import akka.japi
import akka.stream.Materializer
import akka.stream.io._
import akka.stream.scaladsl._
import akka.util.ByteString
import com.typesafe.config.Config

import scala.collection.immutable
import scala.concurrent.{ ExecutionContext, Future, Promise }
import scala.util.Try
import scala.util.control.NonFatal

class HttpExt(config: Config)(implicit system: ActorSystem) extends akka.actor.Extension {

  import Http._

  // configured default HttpsContext for the client-side
  // SYNCHRONIZED ACCESS ONLY!
  private[this] var _defaultClientHttpsContext: HttpsContext = _

  // ** SERVER ** //

  /**
   * Creates a [[Source]] of [[IncomingConnection]] instances which represents a prospective HTTP server binding
   * on the given `endpoint`.
   * If the given port is 0 the resulting source can be materialized several times. Each materialization will
   * then be assigned a new local port by the operating system, which can then be retrieved by the materialized
   * [[ServerBinding]].
   * If the given port is non-zero subsequent materialization attempts of the produced source will immediately
   * fail, unless the first materialization has already been unbound. Unbinding can be triggered via the materialized
   * [[ServerBinding]].
   *
   * If an [[HttpsContext]] is given it will be used for setting up TLS encryption on the binding.
   * Otherwise the binding will be unencrypted.
   *
   * If no ``port`` is explicitly given (or the port value is negative) the protocol's default port will be used,
   * which is 80 for HTTP and 443 for HTTPS.
   *
   * To configure additional settings for a server started using this method,
   * use the `akka.http.server` config section or pass in a [[ServerSettings]] explicitly.
   */
  def bind(interface: String, port: Int = -1,
           settings: ServerSettings = ServerSettings(system),
           httpsContext: Option[HttpsContext] = None,
           log: LoggingAdapter = system.log)(implicit fm: Materializer): Source[IncomingConnection, Future[ServerBinding]] = {
    val effectivePort = if (port >= 0) port else if (httpsContext.isEmpty) 80 else 443
    val tlsStage = sslTlsStage(httpsContext, Server)
    val connections: Source[Tcp.IncomingConnection, Future[Tcp.ServerBinding]] =
      Tcp().bind(interface, effectivePort, settings.backlog, settings.socketOptions, halfClose = false, settings.timeouts.idleTimeout)
    connections.map {
      case Tcp.IncomingConnection(localAddress, remoteAddress, flow) ⇒
        val layer = serverLayer(settings, Some(remoteAddress), log)
        IncomingConnection(localAddress, remoteAddress, layer atop tlsStage join flow)
    }.mapMaterializedValue {
      _.map(tcpBinding ⇒ ServerBinding(tcpBinding.localAddress)(() ⇒ tcpBinding.unbind()))(fm.executionContext)
    }
  }

  /**
   * Convenience method which starts a new HTTP server at the given endpoint and uses the given ``handler``
   * [[Flow]] for processing all incoming connections.
   *
   * The number of concurrently accepted connections can be configured by overriding
   * the `akka.http.server.max-connections` setting.
   *
   * To configure additional settings for a server started using this method,
   * use the `akka.http.server` config section or pass in a [[ServerSettings]] explicitly.
   */
  def bindAndHandle(handler: Flow[HttpRequest, HttpResponse, Any],
                    interface: String, port: Int = -1,
                    settings: ServerSettings = ServerSettings(system),
                    httpsContext: Option[HttpsContext] = None,
                    log: LoggingAdapter = system.log)(implicit fm: Materializer): Future[ServerBinding] = {
    def handleOneConnection(incomingConnection: IncomingConnection): Future[Unit] =
      try
        incomingConnection.flow
          .viaMat(StreamUtils.identityFinishReporter)(Keep.right)
          .joinMat(handler)(Keep.left)
          .run()
      catch {
        case NonFatal(e) ⇒
          log.error(e, "Could not materialize handling flow for {}", incomingConnection)
          throw e
      }

    bind(interface, port, settings, httpsContext, log)
      .mapAsyncUnordered(settings.maxConnections) { connection ⇒
        handleOneConnection(connection).recoverWith {
          // Ignore incoming errors from the connection as they will cancel the binding.
          // As far as it is known currently, these errors can only happen if a TCP error bubbles up
          // from the TCP layer through the HTTP layer to the Http.IncomingConnection.flow.
          // See https://github.com/akka/akka/issues/17992
          case NonFatal(_) ⇒ Future.successful(())
        }(fm.executionContext)
      }
      .to(Sink.ignore)
      .run()
  }

  /**
   * Convenience method which starts a new HTTP server at the given endpoint and uses the given ``handler``
   * [[Flow]] for processing all incoming connections.
   *
   * The number of concurrently accepted connections can be configured by overriding
   * the `akka.http.server.max-connections` setting.
   *
   * To configure additional settings for a server started using this method,
   * use the `akka.http.server` config section or pass in a [[ServerSettings]] explicitly.
   */
  def bindAndHandleSync(handler: HttpRequest ⇒ HttpResponse,
                        interface: String, port: Int = -1,
                        settings: ServerSettings = ServerSettings(system),
                        httpsContext: Option[HttpsContext] = None,
                        log: LoggingAdapter = system.log)(implicit fm: Materializer): Future[ServerBinding] =
    bindAndHandle(Flow[HttpRequest].map(handler), interface, port, settings, httpsContext, log)

  /**
   * Convenience method which starts a new HTTP server at the given endpoint and uses the given ``handler``
   * [[Flow]] for processing all incoming connections.
   *
   * The number of concurrently accepted connections can be configured by overriding
   * the `akka.http.server.max-connections` setting.
   *
   * To configure additional settings for a server started using this method,
   * use the `akka.http.server` config section or pass in a [[ServerSettings]] explicitly.
   */
  def bindAndHandleAsync(handler: HttpRequest ⇒ Future[HttpResponse],
                         interface: String, port: Int = -1,
                         settings: ServerSettings = ServerSettings(system),
                         httpsContext: Option[HttpsContext] = None,
                         parallelism: Int = 1,
                         log: LoggingAdapter = system.log)(implicit fm: Materializer): Future[ServerBinding] =
    bindAndHandle(Flow[HttpRequest].mapAsync(parallelism)(handler), interface, port, settings, httpsContext, log)

  type ServerLayer = Http.ServerLayer

  /**
   * Constructs a [[ServerLayer]] stage using the configured default [[ServerSettings]],
   * configured using the `akka.http.server` config section.
   *
   * The returned [[BidiFlow]] can only be materialized once.
   */
  def serverLayer()(implicit mat: Materializer): ServerLayer = serverLayer(ServerSettings(system))

  /**
   * Constructs a [[ServerLayer]] stage using the given [[ServerSettings]]. The returned [[BidiFlow]] isn't reusable and
   * can only be materialized once. The `remoteAddress`, if provided, will be added as a header to each [[HttpRequest]]
   * this layer produces if the `akka.http.server.remote-address-header` configuration option is enabled.
   */
  def serverLayer(settings: ServerSettings,
                  remoteAddress: Option[InetSocketAddress] = None,
                  log: LoggingAdapter = system.log)(implicit mat: Materializer): ServerLayer =
    HttpServerBluePrint(settings, remoteAddress, log)

  // ** CLIENT ** //

  /**
   * Creates a [[Flow]] representing a prospective HTTP client connection to the given endpoint.
   * Every materialization of the produced flow will attempt to establish a new outgoing connection.
   *
   * To configure additional settings for requests made using this method,
   * use the `akka.http.client` config section or pass in a [[ClientConnectionSettings]] explicitly.
   */
  def outgoingConnection(host: String, port: Int = 80,
                         localAddress: Option[InetSocketAddress] = None,
                         settings: ClientConnectionSettings = ClientConnectionSettings(system),
                         log: LoggingAdapter = system.log): Flow[HttpRequest, HttpResponse, Future[OutgoingConnection]] =
    _outgoingConnection(host, port, localAddress, settings, None, log)

  /**
   * Same as [[outgoingConnection]] but for encrypted (HTTPS) connections.
   *
   * If an explicit [[HttpsContext]] is given then it rather than the configured default [[HttpsContext]] will be used
   * for encryption on the connection.
   *
   * To configure additional settings for requests made using this method,
   * use the `akka.http.client` config section or pass in a [[ClientConnectionSettings]] explicitly.
   */
  def outgoingConnectionTls(host: String, port: Int = 443,
                            localAddress: Option[InetSocketAddress] = None,
                            settings: ClientConnectionSettings = ClientConnectionSettings(system),
                            httpsContext: Option[HttpsContext] = None,
                            log: LoggingAdapter = system.log): Flow[HttpRequest, HttpResponse, Future[OutgoingConnection]] =
    _outgoingConnection(host, port, localAddress, settings, effectiveHttpsContext(httpsContext), log)

  private def _outgoingConnection(host: String, port: Int, localAddress: Option[InetSocketAddress],
                                  settings: ClientConnectionSettings, httpsContext: Option[HttpsContext],
                                  log: LoggingAdapter): Flow[HttpRequest, HttpResponse, Future[OutgoingConnection]] = {
    val hostHeader = if (port == (if (httpsContext.isEmpty) 80 else 443)) Host(host) else Host(host, port)
    val layer = clientLayer(hostHeader, settings, log)
    layer.joinMat(_outgoingTlsConnectionLayer(host, port, localAddress, settings, httpsContext, log))(Keep.right)
  }

  private def _outgoingTlsConnectionLayer(host: String, port: Int, localAddress: Option[InetSocketAddress],
                                          settings: ClientConnectionSettings, httpsContext: Option[HttpsContext],
                                          log: LoggingAdapter): Flow[SslTlsOutbound, SslTlsInbound, Future[OutgoingConnection]] = {
    val tlsStage = sslTlsStage(httpsContext, Client, Some(host -> port))
    val transportFlow = Tcp().outgoingConnection(new InetSocketAddress(host, port), localAddress,
      settings.socketOptions, halfClose = true, settings.connectingTimeout, settings.idleTimeout)

    tlsStage.joinMat(transportFlow) { (_, tcpConnFuture) ⇒
      import system.dispatcher
      tcpConnFuture map { tcpConn ⇒ OutgoingConnection(tcpConn.localAddress, tcpConn.remoteAddress) }
    }
  }

  type ClientLayer = Http.ClientLayer

  /**
   * Constructs a [[ClientLayer]] stage using the configured default [[ClientConnectionSettings]],
   * configured using the `akka.http.client` config section.
   */
  def clientLayer(hostHeader: Host): ClientLayer =
    clientLayer(hostHeader, ClientConnectionSettings(system))

  /**
   * Constructs a [[ClientLayer]] stage using the given [[ClientConnectionSettings]].
   */
  def clientLayer(hostHeader: Host,
                  settings: ClientConnectionSettings,
                  log: LoggingAdapter = system.log): ClientLayer =
    OutgoingConnectionBlueprint(hostHeader, settings, log)

  // ** CONNECTION POOL ** //

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
   *
   * To configure additional settings for the pool (and requests made using it),
   * use the `akka.http.host-connection-pool` config section or pass in a [[ConnectionPoolSettings]] explicitly.
   */
  def newHostConnectionPool[T](host: String, port: Int = 80,
                               settings: ConnectionPoolSettings = ConnectionPoolSettings(system),
                               log: LoggingAdapter = system.log)(implicit fm: Materializer): Flow[(HttpRequest, T), (Try[HttpResponse], T), HostConnectionPool] = {
    val cps = ConnectionPoolSetup(settings, None, log)
    newHostConnectionPool(HostConnectionPoolSetup(host, port, cps))
  }

  /**
   * Same as [[newHostConnectionPool]] but for encrypted (HTTPS) connections.
   *
   * If an explicit [[HttpsContext]] is given then it rather than the configured default [[HttpsContext]] will be used
   * for encryption on the connections.
   *
   * To configure additional settings for the pool (and requests made using it),
   * use the `akka.http.host-connection-pool` config section or pass in a [[ConnectionPoolSettings]] explicitly.
   */
  def newHostConnectionPoolTls[T](host: String, port: Int = 443,
                                  settings: ConnectionPoolSettings = ConnectionPoolSettings(system),
                                  httpsContext: Option[HttpsContext] = None,
                                  log: LoggingAdapter = system.log)(implicit fm: Materializer): Flow[(HttpRequest, T), (Try[HttpResponse], T), HostConnectionPool] = {
    val cps = ConnectionPoolSetup(settings, effectiveHttpsContext(httpsContext), log)
    newHostConnectionPool(HostConnectionPoolSetup(host, port, cps))
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
    implicit fm: Materializer): Flow[(HttpRequest, T), (Try[HttpResponse], T), HostConnectionPool] = {
    val gatewayFuture = FastFuture.successful(new PoolGateway(setup, Promise()))
    gatewayClientFlow(setup, gatewayFuture)
  }

  /**
   * Returns a [[Flow]] which dispatches incoming HTTP requests to the per-ActorSystem pool of outgoing
   * HTTP connections to the given target host endpoint. For every ActorSystem, target host and pool
   * configuration a separate connection pool is maintained.
   * The HTTP layer transparently manages idle shutdown and restarting of connections pools as configured.
   * The returned [[Flow]] instances therefore remain valid throughout the lifetime of the application.
   *
   * The internal caching logic guarantees that there will never be more than a single pool running for the
   * given target host endpoint and configuration (in this ActorSystem).
   *
   * Since the underlying transport usually comprises more than a single connection the produced flow might generate
   * responses in an order that doesn't directly match the consumed requests.
   * For example, if two requests A and B enter the flow in that order the response for B might be produced before the
   * response for A.
   * In order to allow for easy response-to-request association the flow takes in a custom, opaque context
   * object of type ``T`` from the application which is emitted together with the corresponding response.
   *
   * To configure additional settings for the pool (and requests made using it),
   * use the `akka.http.host-connection-pool` config section or pass in a [[ConnectionPoolSettings]] explicitly.
   */
  def cachedHostConnectionPool[T](host: String, port: Int = 80,
                                  settings: ConnectionPoolSettings = ConnectionPoolSettings(system),
                                  log: LoggingAdapter = system.log)(implicit fm: Materializer): Flow[(HttpRequest, T), (Try[HttpResponse], T), HostConnectionPool] = {
    val cps = ConnectionPoolSetup(settings, None, log)
    val setup = HostConnectionPoolSetup(host, port, cps)
    cachedHostConnectionPool(setup)
  }

  /**
   * Same as [[cachedHostConnectionPool]] but for encrypted (HTTPS) connections.
   *
   * If an explicit [[HttpsContext]] is given then it rather than the configured default [[HttpsContext]] will be used
   * for encryption on the connections.
   *
   * To configure additional settings for the pool (and requests made using it),
   * use the `akka.http.host-connection-pool` config section or pass in a [[ConnectionPoolSettings]] explicitly.
   */
  def cachedHostConnectionPoolTls[T](host: String, port: Int = 443,
                                     settings: ConnectionPoolSettings = ConnectionPoolSettings(system),
                                     httpsContext: Option[HttpsContext] = None,
                                     log: LoggingAdapter = system.log)(implicit fm: Materializer): Flow[(HttpRequest, T), (Try[HttpResponse], T), HostConnectionPool] = {
    val cps = ConnectionPoolSetup(settings, effectiveHttpsContext(httpsContext), log)
    val setup = HostConnectionPoolSetup(host, port, cps)
    cachedHostConnectionPool(setup)
  }

  /**
   * Returns a [[Flow]] which dispatches incoming HTTP requests to the per-ActorSystem pool of outgoing
   * HTTP connections to the given target host endpoint. For every ActorSystem, target host and pool
   * configuration a separate connection pool is maintained.
   * The HTTP layer transparently manages idle shutdown and restarting of connections pools as configured.
   * The returned [[Flow]] instances therefore remain valid throughout the lifetime of the application.
   *
   * The internal caching logic guarantees that there will never be more than a single pool running for the
   * given target host endpoint and configuration (in this ActorSystem).
   *
   * Since the underlying transport usually comprises more than a single connection the produced flow might generate
   * responses in an order that doesn't directly match the consumed requests.
   * For example, if two requests A and B enter the flow in that order the response for B might be produced before the
   * response for A.
   * In order to allow for easy response-to-request association the flow takes in a custom, opaque context
   * object of type ``T`` from the application which is emitted together with the corresponding response.
   */
  def cachedHostConnectionPool[T](setup: HostConnectionPoolSetup)(
    implicit fm: Materializer): Flow[(HttpRequest, T), (Try[HttpResponse], T), HostConnectionPool] =
    gatewayClientFlow(setup, cachedGateway(setup))

  /**
   * Creates a new "super connection pool flow", which routes incoming requests to a (cached) host connection pool
   * depending on their respective effective URIs. Note that incoming requests must have an absolute URI.
   *
   * If an explicit [[HttpsContext]] is given then it rather than the configured default [[HttpsContext]] will be used
   * for setting up HTTPS connection pools, if required.
   *
   * Since the underlying transport usually comprises more than a single connection the produced flow might generate
   * responses in an order that doesn't directly match the consumed requests.
   * For example, if two requests A and B enter the flow in that order the response for B might be produced before the
   * response for A.
   * In order to allow for easy response-to-request association the flow takes in a custom, opaque context
   * object of type ``T`` from the application which is emitted together with the corresponding response.
   *
   * To configure additional settings for the pool (and requests made using it),
   * use the `akka.http.host-connection-pool` config section or pass in a [[ConnectionPoolSettings]] explicitly.
   */
  def superPool[T](settings: ConnectionPoolSettings = ConnectionPoolSettings(system),
                   httpsContext: Option[HttpsContext] = None,
                   log: LoggingAdapter = system.log)(implicit fm: Materializer): Flow[(HttpRequest, T), (Try[HttpResponse], T), Unit] =
    clientFlow[T](settings) { request ⇒ request -> cachedGateway(request, settings, httpsContext, log) }

  /**
   * Fires a single [[HttpRequest]] across the (cached) host connection pool for the request's
   * effective URI to produce a response future.
   *
   * If an explicit [[HttpsContext]] is given then it rather than the configured default [[HttpsContext]] will be used
   * for setting up the HTTPS connection pool, if required.
   *
   * Note that the request must have an absolute URI, otherwise the future will be completed with an error.
   */
  def singleRequest(request: HttpRequest,
                    settings: ConnectionPoolSettings = ConnectionPoolSettings(system),
                    httpsContext: Option[HttpsContext] = None,
                    log: LoggingAdapter = system.log)(implicit fm: Materializer): Future[HttpResponse] =
    try {
      val gatewayFuture = cachedGateway(request, settings, httpsContext, log)
      gatewayFuture.flatMap(_(request))(fm.executionContext)
    } catch {
      case e: IllegalUriException ⇒ FastFuture.failed(e)
    }

  /**
   * Constructs a [[WebsocketClientLayer]] stage using the configured default [[ClientConnectionSettings]],
   * configured using the `akka.http.client` config section.
   *
   * The layer is not reusable and must only be materialized once.
   */
  def websocketClientLayer(request: WebsocketRequest,
                           settings: ClientConnectionSettings = ClientConnectionSettings(system),
                           log: LoggingAdapter = system.log): Http.WebsocketClientLayer =
    WebsocketClientBlueprint(request, settings, log)

  /**
   * Constructs a flow that once materialized establishes a Websocket connection to the given Uri.
   *
   * The layer is not reusable and must only be materialized once.
   */
  def websocketClientFlow(request: WebsocketRequest,
                          localAddress: Option[InetSocketAddress] = None,
                          settings: ClientConnectionSettings = ClientConnectionSettings(system),
                          httpsContext: Option[HttpsContext] = None,
                          log: LoggingAdapter = system.log): Flow[Message, Message, Future[WebsocketUpgradeResponse]] = {
    import request.uri
    require(uri.isAbsolute, s"Websocket request URI must be absolute but was '$uri'")

    val ctx = uri.scheme match {
      case "ws"  ⇒ None
      case "wss" ⇒ effectiveHttpsContext(httpsContext)
      case scheme @ _ ⇒
        throw new IllegalArgumentException(s"Illegal URI scheme '$scheme' in '$uri' for Websocket request. " +
          s"Websocket requests must use either 'ws' or 'wss'")
    }
    val host = uri.authority.host.address
    val port = uri.effectivePort

    websocketClientLayer(request, settings, log)
      .joinMat(_outgoingTlsConnectionLayer(host, port, localAddress, settings, ctx, log))(Keep.left)
  }

  /**
   * Runs a single Websocket conversation given a Uri and a flow that represents the client side of the
   * Websocket conversation.
   */
  def singleWebsocketRequest[T](request: WebsocketRequest,
                                clientFlow: Flow[Message, Message, T],
                                localAddress: Option[InetSocketAddress] = None,
                                settings: ClientConnectionSettings = ClientConnectionSettings(system),
                                httpsContext: Option[HttpsContext] = None,
                                log: LoggingAdapter = system.log)(implicit mat: Materializer): (Future[WebsocketUpgradeResponse], T) =
    websocketClientFlow(request, localAddress, settings, httpsContext, log)
      .joinMat(clientFlow)(Keep.both).run()

  /**
   * Triggers an orderly shutdown of all host connections pools currently maintained by the [[ActorSystem]].
   * The returned future is completed when all pools that were live at the time of this method call
   * have completed their shutdown process.
   *
   * If existing pool client flows are re-used or new ones materialized concurrently with or after this
   * method call the respective connection pools will be restarted and not contribute to the returned future.
   */
  def shutdownAllConnectionPools(): Future[Unit] = {
    import system.dispatcher

    import scala.collection.JavaConverters._
    val gateways = hostPoolCache.values().asScala
    system.log.debug("Initiating orderly shutdown of all active host connections pools...")
    Future.sequence(gateways.map(_.flatMap(_.shutdown()))).map(_ ⇒ ())
  }

  /**
   * Gets the current default client-side [[HttpsContext]].
   */
  def defaultClientHttpsContext: HttpsContext =
    synchronized {
      _defaultClientHttpsContext match {
        case null ⇒
          val ctx = createDefaultClientHttpsContext
          _defaultClientHttpsContext = ctx
          ctx
        case ctx ⇒ ctx
      }
    }

  private def createDefaultClientHttpsContext: HttpsContext = {
    val defaultCtx = SSLContext.getDefault

    val params = new SSLParameters
    if (!Java6Compat.setEndpointIdentificationAlgorithm(params, "https"))
      throw new ReadTheDocumentationException(
        "Cannot enable HTTPS hostname verification on Java 6. See the " +
          "\"Client-Side HTTPS Support\" section in the documentation")

    HttpsContext(defaultCtx, sslParameters = Some(params))
  }

  /**
   * Sets the default client-side [[HttpsContext]].
   */
  def setDefaultClientHttpsContext(context: HttpsContext): Unit =
    synchronized {
      _defaultClientHttpsContext = context
    }

  // every ActorSystem maintains its own connection pools
  private[this] val hostPoolCache = new ConcurrentHashMap[HostConnectionPoolSetup, Future[PoolGateway]]

  private def cachedGateway(request: HttpRequest,
                            settings: ConnectionPoolSettings, httpsContext: Option[HttpsContext],
                            log: LoggingAdapter)(implicit fm: Materializer): Future[PoolGateway] =
    if (request.uri.scheme.nonEmpty && request.uri.authority.nonEmpty) {
      val httpsCtx = if (request.uri.scheme.equalsIgnoreCase("https")) effectiveHttpsContext(httpsContext) else None
      val setup = ConnectionPoolSetup(settings, httpsCtx, log)
      val host = request.uri.authority.host.toString()
      val hcps = HostConnectionPoolSetup(host, request.uri.effectivePort, setup)
      cachedGateway(hcps)
    } else {
      val msg = s"Cannot determine request scheme and target endpoint as ${request.method} request to ${request.uri} doesn't have an absolute URI"
      throw new IllegalUriException(ErrorInfo(msg))
    }

  private[http] def cachedGateway(setup: HostConnectionPoolSetup)(implicit fm: Materializer): Future[PoolGateway] = {
    val gatewayPromise = Promise[PoolGateway]()
    hostPoolCache.putIfAbsent(setup, gatewayPromise.future) match {
      case null ⇒ // only one thread can get here at a time
        val whenShuttingDown = Promise[Unit]()
        val gateway =
          try new PoolGateway(setup, whenShuttingDown)
          catch {
            case NonFatal(e) ⇒
              hostPoolCache.remove(setup)
              gatewayPromise.failure(e)
              throw e
          }
        val fastFuture = FastFuture.successful(gateway)
        hostPoolCache.put(setup, fastFuture) // optimize subsequent gateway accesses
        gatewayPromise.success(gateway) // satisfy everyone who got a hold of our promise while we were starting up
        whenShuttingDown.future.onComplete(_ ⇒ hostPoolCache.remove(setup, fastFuture))(fm.executionContext)
        fastFuture

      case future ⇒ future // return cached instance
    }
  }

  private def gatewayClientFlow[T](hcps: HostConnectionPoolSetup,
                                   gatewayFuture: Future[PoolGateway])(
                                     implicit fm: Materializer): Flow[(HttpRequest, T), (Try[HttpResponse], T), HostConnectionPool] =
    clientFlow[T](hcps.setup.settings)(_ -> gatewayFuture)
      .mapMaterializedValue(_ ⇒ HostConnectionPool(hcps)(gatewayFuture))

  private def clientFlow[T](settings: ConnectionPoolSettings)(f: HttpRequest ⇒ (HttpRequest, Future[PoolGateway]))(
    implicit system: ActorSystem, fm: Materializer): Flow[(HttpRequest, T), (Try[HttpResponse], T), Unit] = {
    // a connection pool can never have more than pipeliningLimit * maxConnections requests in flight at any point
    val parallelism = settings.pipeliningLimit * settings.maxConnections
    Flow[(HttpRequest, T)].mapAsyncUnordered(parallelism) {
      case (request, userContext) ⇒
        val (effectiveRequest, gatewayFuture) = f(request)
        val result = Promise[(Try[HttpResponse], T)]() // TODO: simplify to `transformWith` when on Scala 2.12
        gatewayFuture
          .flatMap(_(effectiveRequest))(fm.executionContext)
          .onComplete(responseTry ⇒ result.success(responseTry -> userContext))(fm.executionContext)
        result.future
    }
  }

  private def effectiveHttpsContext(ctx: Option[HttpsContext]): Option[HttpsContext] =
    ctx orElse Some(defaultClientHttpsContext)

  private[http] def sslTlsStage(httpsContext: Option[HttpsContext], role: Role, hostInfo: Option[(String, Int)] = None) =
    httpsContext match {
      case Some(hctx) ⇒ SslTls(hctx.sslContext, hctx.firstSession, role, hostInfo = hostInfo)
      case None       ⇒ SslTlsPlacebo.forScala
    }
}

object Http extends ExtensionId[HttpExt] with ExtensionIdProvider {

  //#server-layer
  /**
   * The type of the server-side HTTP layer as a stand-alone BidiFlow
   * that can be put atop the TCP layer to form an HTTP server.
   *
   * {{{
   *                +------+
   * HttpResponse ~>|      |~> SslTlsOutbound
   *                | bidi |
   * HttpRequest  <~|      |<~ SslTlsInbound
   *                +------+
   * }}}
   */
  type ServerLayer = BidiFlow[HttpResponse, SslTlsOutbound, SslTlsInbound, HttpRequest, Unit]
  //#

  //#client-layer
  /**
   * The type of the client-side HTTP layer as a stand-alone BidiFlow
   * that can be put atop the TCP layer to form an HTTP client.
   *
   * {{{
   *                +------+
   * HttpRequest  ~>|      |~> SslTlsOutbound
   *                | bidi |
   * HttpResponse <~|      |<~ SslTlsInbound
   *                +------+
   * }}}
   */
  type ClientLayer = BidiFlow[HttpRequest, SslTlsOutbound, SslTlsInbound, HttpResponse, Unit]
  //#

  /**
   * The type of the client-side Websocket layer as a stand-alone BidiFlow
   * that can be put atop the TCP layer to form an HTTP client.
   *
   * {{{
   *                +------+
   * ws.Message   ~>|      |~> SslTlsOutbound
   *                | bidi |
   * ws.Message   <~|      |<~ SslTlsInbound
   *                +------+
   * }}}
   */
  type WebsocketClientLayer = BidiFlow[Message, SslTlsOutbound, SslTlsInbound, Message, Future[WebsocketUpgradeResponse]]

  /**
   * Represents a prospective HTTP server binding.
   *
   * @param localAddress  The local address of the endpoint bound by the materialization of the `connections` [[Source]]
   *
   */
  final case class ServerBinding(localAddress: InetSocketAddress)(private val unbindAction: () ⇒ Future[Unit]) {

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
  final case class IncomingConnection(
    localAddress: InetSocketAddress,
    remoteAddress: InetSocketAddress,
    flow: Flow[HttpResponse, HttpRequest, Unit]) {

    /**
     * Handles the connection with the given flow, which is materialized exactly once
     * and the respective materialization result returned.
     */
    def handleWith[Mat](handler: Flow[HttpRequest, HttpResponse, Mat])(implicit fm: Materializer): Mat =
      flow.joinMat(handler)(Keep.right).run()

    /**
     * Handles the connection with the given handler function.
     * Returns the materialization result of the underlying flow materialization.
     */
    def handleWithSyncHandler(handler: HttpRequest ⇒ HttpResponse)(implicit fm: Materializer): Unit =
      handleWith(Flow[HttpRequest].map(handler))

    /**
     * Handles the connection with the given handler function.
     * Returns the materialization result of the underlying flow materialization.
     */
    def handleWithAsyncHandler(handler: HttpRequest ⇒ Future[HttpResponse])(implicit fm: Materializer): Unit =
      handleWith(Flow[HttpRequest].mapAsync(1)(handler))
  }

  /**
   * Represents a prospective outgoing HTTP connection.
   */
  final case class OutgoingConnection(localAddress: InetSocketAddress, remoteAddress: InetSocketAddress)

  /**
   * Represents a connection pool to a specific target host and pool configuration.
   */
  final case class HostConnectionPool(setup: HostConnectionPoolSetup)(
    private[http] val gatewayFuture: Future[PoolGateway]) extends javadsl.HostConnectionPool { // enable test access

    /**
     * Asynchronously triggers the shutdown of the host connection pool.
     *
     * The produced [[Future]] is fulfilled when the shutdown has been completed.
     */
    def shutdown()(implicit ec: ExecutionContext): Future[Unit] = gatewayFuture.flatMap(_.shutdown())
  }

  //////////////////// EXTENSION SETUP ///////////////////

  def apply()(implicit system: ActorSystem): HttpExt = super.apply(system)

  def lookup() = Http

  def createExtension(system: ExtendedActorSystem): HttpExt =
    new HttpExt(system.settings.config getConfig "akka.http")(system)
}

import scala.collection.JavaConverters._

//# https-context-impl
final case class HttpsContext(sslContext: SSLContext,
                              enabledCipherSuites: Option[immutable.Seq[String]] = None,
                              enabledProtocols: Option[immutable.Seq[String]] = None,
                              clientAuth: Option[ClientAuth] = None,
                              sslParameters: Option[SSLParameters] = None)
  //#
  extends akka.http.javadsl.HttpsContext {
  def firstSession = NegotiateNewSession(enabledCipherSuites, enabledProtocols, clientAuth, sslParameters)

  /** Java API */
  def getSslContext: SSLContext = sslContext

  /** Java API */
  def getEnabledCipherSuites: japi.Option[JCollection[String]] = enabledCipherSuites.map(_.asJavaCollection)

  /** Java API */
  def getEnabledProtocols: japi.Option[JCollection[String]] = enabledProtocols.map(_.asJavaCollection)

  /** Java API */
  def getClientAuth: japi.Option[ClientAuth] = clientAuth

  /** Java API */
  def getSslParameters: japi.Option[SSLParameters] = sslParameters
}