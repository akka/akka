/**
 * Copyright (C) 2009-2016 Lightbend Inc. <http://www.lightbend.com>
 */

package akka.http.scaladsl

import java.net.InetSocketAddress
import java.util.concurrent.CompletionStage
import javax.net.ssl._

import akka.actor._
import akka.dispatch.ExecutionContexts
import akka.event.{ Logging, LoggingAdapter }
import akka.http.impl.engine.HttpConnectionIdleTimeoutBidi
import akka.http.impl.engine.client.PoolMasterActor.{ PoolSize, ShutdownAll }
import akka.http.impl.engine.client._
import akka.http.impl.engine.server._
import akka.http.impl.engine.ws.WebSocketClientBlueprint
import akka.http.impl.settings.{ ConnectionPoolSetup, HostConnectionPoolSetup }
import akka.http.impl.util.StreamUtils
import akka.http.scaladsl.model._
import akka.http.scaladsl.model.headers.Host
import akka.http.scaladsl.model.ws.{ Message, WebSocketRequest, WebSocketUpgradeResponse }
import akka.http.scaladsl.settings.{ ClientConnectionSettings, ConnectionPoolSettings, ServerSettings }
import akka.http.scaladsl.util.FastFuture
import akka.{ Done, NotUsed }
import akka.stream._
import akka.stream.TLSProtocol._
import akka.stream.scaladsl._
import akka.util.ByteString
import com.typesafe.config.Config
import com.typesafe.sslconfig.akka._
import com.typesafe.sslconfig.akka.util.AkkaLoggerFactory
import com.typesafe.sslconfig.ssl.ConfigSSLContextBuilder

import scala.concurrent._
import scala.util.Try
import scala.util.control.NonFatal
import scala.compat.java8.FutureConverters._
import scala.concurrent.duration.{ Duration, FiniteDuration }

class HttpExt(private val config: Config)(implicit val system: ActorSystem) extends akka.actor.Extension
  with DefaultSSLContextCreation {

  akka.http.Version.check(system.settings.config)

  import Http._

  override val sslConfig = AkkaSSLConfig(system)
  validateAndWarnAboutLooseSettings()

  private[this] val defaultConnectionPoolSettings = ConnectionPoolSettings(system)

  // configured default HttpsContext for the client-side
  // SYNCHRONIZED ACCESS ONLY!
  private[this] var _defaultClientHttpsConnectionContext: HttpsConnectionContext = _
  private[this] var _defaultServerConnectionContext: ConnectionContext = _

  // ** SERVER ** //

  private[this] final val DefaultPortForProtocol = -1 // any negative value

  private type ServerLayerBidiFlow = BidiFlow[HttpResponse, ByteString, ByteString, HttpRequest, NotUsed]
  private type ServerLayerFlow = Flow[ByteString, ByteString, Future[Done]]

  private def fuseServerBidiFlow(
    settings:          ServerSettings,
    connectionContext: ConnectionContext,
    log:               LoggingAdapter)(implicit mat: Materializer): ServerLayerBidiFlow = {
    val httpLayer = serverLayer(settings, None, log, connectionContext.isSecure)
    val tlsStage = sslTlsStage(connectionContext, Server)

    val serverBidiFlow =
      settings.idleTimeout match {
        case t: FiniteDuration ⇒ httpLayer atop tlsStage atop HttpConnectionIdleTimeoutBidi(t, None)
        case _                 ⇒ httpLayer atop tlsStage
      }

    serverBidiFlow
  }

  private def fuseServerFlow(
    baseFlow: ServerLayerBidiFlow,
    handler:  Flow[HttpRequest, HttpResponse, Any])(implicit mat: Materializer): ServerLayerFlow =
    Flow.fromGraph(
      Fusing.aggressive(
        Flow[HttpRequest]
          .watchTermination()(Keep.right)
          .viaMat(handler)(Keep.left)
          .watchTermination() { (termWatchBefore, termWatchAfter) ⇒
            // flag termination when the user handler has gotten (or has emitted) termination
            // signals in both directions
            termWatchBefore.flatMap(_ ⇒ termWatchAfter)(ExecutionContexts.sameThreadExecutionContext)
          }
          .joinMat(baseFlow)(Keep.left)
      )
    )

  private def tcpBind(interface: String, port: Int, settings: ServerSettings): Source[Tcp.IncomingConnection, Future[Tcp.ServerBinding]] = {
    Tcp()
      .bind(
        interface,
        port,
        settings.backlog,
        settings.socketOptions,
        halfClose = false,
        idleTimeout = Duration.Inf // we knowingly disable idle-timeout on TCP level, as we handle it explicitly in Akka HTTP itself
      )
      .map { incoming ⇒
        val newFlow =
          incoming.flow
            // Prevent cancellation from the Http implementation to reach the TCP streams to prevent
            // completion / cancellation race towards TCP streams. See #459.
            //
            // This could create a potential resource leak, if, e.g. because of a bug, the HTTP implementation doesn't
            // close the write-side of the connection at the same time as it cancels the read side and if the client
            // never closes the connection after or while reading the response. Fortunately, this will be handled by
            // the idle-timeout which will forcibly close the connection after a defined amount of inactivity.
            .via(StreamUtils.absorbCancellation)
        incoming.copy(flow = newFlow)
      }
  }

  private def choosePort(port: Int, connectionContext: ConnectionContext) = if (port >= 0) port else connectionContext.defaultPort

  private def materializeTcpBind(binding: Future[Tcp.ServerBinding])(implicit mat: Materializer) = binding
    .map(tcpBinding ⇒ ServerBinding(tcpBinding.localAddress)(() ⇒ tcpBinding.unbind()))(mat.executionContext)

  private def prepareAttributes(settings: ServerSettings, remoteAddress: InetSocketAddress) =
    if (settings.remoteAddressHeader) HttpAttributes.remoteAddress(Some(remoteAddress))
    else HttpAttributes.empty

  /**
   * Creates a [[akka.stream.scaladsl.Source]] of [[akka.http.scaladsl.Http.IncomingConnection]] instances which represents a prospective HTTP server binding
   * on the given `endpoint`.
   *
   * If the given port is 0 the resulting source can be materialized several times. Each materialization will
   * then be assigned a new local port by the operating system, which can then be retrieved by the materialized
   * [[akka.http.scaladsl.Http.ServerBinding]].
   *
   * If the given port is non-zero subsequent materialization attempts of the produced source will immediately
   * fail, unless the first materialization has already been unbound. Unbinding can be triggered via the materialized
   * [[akka.http.scaladsl.Http.ServerBinding]].
   *
   * If an [[ConnectionContext]] is given it will be used for setting up TLS encryption on the binding.
   * Otherwise the binding will be unencrypted.
   *
   * If no `port` is explicitly given (or the port value is negative) the protocol's default port will be used,
   * which is 80 for HTTP and 443 for HTTPS.
   *
   * To configure additional settings for a server started using this method,
   * use the `akka.http.server` config section or pass in a [[akka.http.scaladsl.settings.ServerSettings]] explicitly.
   */
  def bind(interface: String, port: Int = DefaultPortForProtocol,
           connectionContext: ConnectionContext = defaultServerHttpContext,
           settings:          ServerSettings    = ServerSettings(system),
           log:               LoggingAdapter    = system.log)(implicit fm: Materializer): Source[Http.IncomingConnection, Future[ServerBinding]] = {
    val fullLayer = fuseServerBidiFlow(settings, connectionContext, log)

    tcpBind(interface, choosePort(port, connectionContext), settings)
      .map(incoming ⇒ {
        val serverFlow = fullLayer.addAttributes(prepareAttributes(settings, incoming.remoteAddress)) join incoming.flow
        IncomingConnection(incoming.localAddress, incoming.remoteAddress, serverFlow)
      })
      .mapMaterializedValue(materializeTcpBind)
  }

  /**
   * Convenience method which starts a new HTTP server at the given endpoint and uses the given `handler`
   * [[akka.stream.scaladsl.Flow]] for processing all incoming connections.
   *
   * The number of concurrently accepted connections can be configured by overriding
   * the `akka.http.server.max-connections` setting. Please see the documentation in the reference.conf for more
   * information about what kind of guarantees to expect.
   *
   * To configure additional settings for a server started using this method,
   * use the `akka.http.server` config section or pass in a [[akka.http.scaladsl.settings.ServerSettings]] explicitly.
   */
  def bindAndHandle(
    handler:   Flow[HttpRequest, HttpResponse, Any],
    interface: String, port: Int = DefaultPortForProtocol,
    connectionContext: ConnectionContext = defaultServerHttpContext,
    settings:          ServerSettings    = ServerSettings(system),
    log:               LoggingAdapter    = system.log)(implicit fm: Materializer): Future[ServerBinding] = {
    val fullLayer: Flow[ByteString, ByteString, Future[Done]] = fuseServerFlow(fuseServerBidiFlow(settings, connectionContext, log), handler)

    tcpBind(interface, choosePort(port, connectionContext), settings)
      .mapAsyncUnordered(settings.maxConnections) { incoming ⇒
        try {
          fullLayer
            .addAttributes(prepareAttributes(settings, incoming.remoteAddress))
            .joinMat(incoming.flow)(Keep.left)
            .run()
            .recover {
              // Ignore incoming errors from the connection as they will cancel the binding.
              // As far as it is known currently, these errors can only happen if a TCP error bubbles up
              // from the TCP layer through the HTTP layer to the Http.IncomingConnection.flow.
              // See https://github.com/akka/akka/issues/17992
              case NonFatal(ex) ⇒ Done
            }(ExecutionContexts.sameThreadExecutionContext)
        } catch {
          case NonFatal(e) ⇒
            log.error(e, "Could not materialize handling flow for {}", incoming)
            throw e
        }
      }
      .mapMaterializedValue(materializeTcpBind)
      .to(Sink.ignore)
      .run()
  }

  /**
   * Convenience method which starts a new HTTP server at the given endpoint and uses the given `handler`
   * [[akka.stream.scaladsl.Flow]] for processing all incoming connections.
   *
   * The number of concurrently accepted connections can be configured by overriding
   * the `akka.http.server.max-connections` setting. Please see the documentation in the reference.conf for more
   * information about what kind of guarantees to expect.
   *
   * To configure additional settings for a server started using this method,
   * use the `akka.http.server` config section or pass in a [[akka.http.scaladsl.settings.ServerSettings]] explicitly.
   */
  def bindAndHandleSync(
    handler:   HttpRequest ⇒ HttpResponse,
    interface: String, port: Int = DefaultPortForProtocol,
    connectionContext: ConnectionContext = defaultServerHttpContext,
    settings:          ServerSettings    = ServerSettings(system),
    log:               LoggingAdapter    = system.log)(implicit fm: Materializer): Future[ServerBinding] =
    bindAndHandle(Flow[HttpRequest].map(handler), interface, port, connectionContext, settings, log)

  /**
   * Convenience method which starts a new HTTP server at the given endpoint and uses the given `handler`
   * [[akka.stream.scaladsl.Flow]] for processing all incoming connections.
   *
   * The number of concurrently accepted connections can be configured by overriding
   * the `akka.http.server.max-connections` setting. Please see the documentation in the reference.conf for more
   * information about what kind of guarantees to expect.
   *
   * To configure additional settings for a server started using this method,
   * use the `akka.http.server` config section or pass in a [[akka.http.scaladsl.settings.ServerSettings]] explicitly.
   */
  def bindAndHandleAsync(
    handler:   HttpRequest ⇒ Future[HttpResponse],
    interface: String, port: Int = DefaultPortForProtocol,
    connectionContext: ConnectionContext = defaultServerHttpContext,
    settings:          ServerSettings    = ServerSettings(system),
    parallelism:       Int               = 1,
    log:               LoggingAdapter    = system.log)(implicit fm: Materializer): Future[ServerBinding] =
    bindAndHandle(Flow[HttpRequest].mapAsync(parallelism)(handler), interface, port, connectionContext, settings, log)

  type ServerLayer = Http.ServerLayer

  /**
   * Constructs a [[akka.http.scaladsl.Http.ServerLayer]] stage using the configured default [[akka.http.scaladsl.settings.ServerSettings]],
   * configured using the `akka.http.server` config section.
   *
   * The returned [[akka.stream.scaladsl.BidiFlow]] can only be materialized once.
   */
  def serverLayer()(implicit mat: Materializer): ServerLayer = serverLayer(ServerSettings(system))

  /**
   * Constructs a [[akka.http.scaladsl.Http.ServerLayer]] stage using the given [[akka.http.scaladsl.settings.ServerSettings]]. The returned [[akka.stream.scaladsl.BidiFlow]] isn't reusable and
   * can only be materialized once. The `remoteAddress`, if provided, will be added as a header to each [[akka.http.scaladsl.model.HttpRequest]]
   * this layer produces if the `akka.http.server.remote-address-header` configuration option is enabled.
   */
  def serverLayer(
    settings:           ServerSettings,
    remoteAddress:      Option[InetSocketAddress] = None,
    log:                LoggingAdapter            = system.log,
    isSecureConnection: Boolean                   = false)(implicit mat: Materializer): ServerLayer =
    HttpServerBluePrint(settings, log, isSecureConnection)

  // for binary-compatibility, since 10.0.0
  def serverLayer(
    settings:      ServerSettings,
    remoteAddress: Option[InetSocketAddress],
    log:           LoggingAdapter)(implicit mat: Materializer): ServerLayer =
    HttpServerBluePrint(settings, log, false)

  // ** CLIENT ** //

  private[this] val poolMasterActorRef = system.actorOf(PoolMasterActor.props, "pool-master")
  private[this] val systemMaterializer = ActorMaterializer()

  /**
   * Creates a [[akka.stream.scaladsl.Flow]] representing a prospective HTTP client connection to the given endpoint.
   * Every materialization of the produced flow will attempt to establish a new outgoing connection.
   *
   * To configure additional settings for requests made using this method,
   * use the `akka.http.client` config section or pass in a [[akka.http.scaladsl.settings.ClientConnectionSettings]] explicitly.
   */
  def outgoingConnection(host: String, port: Int = 80,
                         localAddress: Option[InetSocketAddress] = None,
                         settings:     ClientConnectionSettings  = ClientConnectionSettings(system),
                         log:          LoggingAdapter            = system.log): Flow[HttpRequest, HttpResponse, Future[OutgoingConnection]] =
    _outgoingConnection(host, port, localAddress, settings, ConnectionContext.noEncryption(), log)

  /**
   * Same as [[#outgoingConnection]] but for encrypted (HTTPS) connections.
   *
   * If an explicit [[HttpsConnectionContext]] is given then it rather than the configured default [[HttpsConnectionContext]] will be used
   * for encryption on the connection.
   *
   * To configure additional settings for requests made using this method,
   * use the `akka.http.client` config section or pass in a [[akka.http.scaladsl.settings.ClientConnectionSettings]] explicitly.
   */
  def outgoingConnectionHttps(host: String, port: Int = 443,
                              connectionContext: HttpsConnectionContext    = defaultClientHttpsContext,
                              localAddress:      Option[InetSocketAddress] = None,
                              settings:          ClientConnectionSettings  = ClientConnectionSettings(system),
                              log:               LoggingAdapter            = system.log): Flow[HttpRequest, HttpResponse, Future[OutgoingConnection]] =
    _outgoingConnection(host, port, localAddress, settings, connectionContext, log)

  private def _outgoingConnection(
    host:              String,
    port:              Int,
    localAddress:      Option[InetSocketAddress],
    settings:          ClientConnectionSettings,
    connectionContext: ConnectionContext,
    log:               LoggingAdapter): Flow[HttpRequest, HttpResponse, Future[OutgoingConnection]] = {
    val hostHeader = if (port == connectionContext.defaultPort) Host(host) else Host(host, port)
    val layer = clientLayer(hostHeader, settings, log)
    layer.joinMat(_outgoingTlsConnectionLayer(host, port, localAddress, settings, connectionContext, log))(Keep.right)
  }

  private def _outgoingTlsConnectionLayer(host: String, port: Int, localAddress: Option[InetSocketAddress],
                                          settings: ClientConnectionSettings, connectionContext: ConnectionContext,
                                          log: LoggingAdapter): Flow[SslTlsOutbound, SslTlsInbound, Future[OutgoingConnection]] = {
    val tlsStage = sslTlsStage(connectionContext, Client, Some(host → port))
    // The InetSocketAddress representing the remote address must be created unresolved because akka.io.TcpOutgoingConnection will
    // not attempt DNS resolution if the InetSocketAddress is already resolved. That behavior is problematic when it comes to
    // connection pools since it means that new connections opened by the pool in the future can end up using a stale IP address.
    // By passing an unresolved InetSocketAddress instead, we ensure that DNS resolution is performed for every new connection.
    val transportFlow = Tcp().outgoingConnection(InetSocketAddress.createUnresolved(host, port), localAddress,
      settings.socketOptions, halfClose = true, settings.connectingTimeout, settings.idleTimeout)

    tlsStage.joinMat(transportFlow) { (_, tcpConnFuture) ⇒
      import system.dispatcher
      tcpConnFuture map { tcpConn ⇒ OutgoingConnection(tcpConn.localAddress, tcpConn.remoteAddress) }
    }
  }

  type ClientLayer = Http.ClientLayer

  /**
   * Constructs a [[akka.http.scaladsl.Http.ClientLayer]] stage using the configured default [[akka.http.scaladsl.settings.ClientConnectionSettings]],
   * configured using the `akka.http.client` config section.
   */
  def clientLayer(hostHeader: Host): ClientLayer =
    clientLayer(hostHeader, ClientConnectionSettings(system))

  /**
   * Constructs a [[akka.http.scaladsl.Http.ClientLayer]] stage using the given [[akka.http.scaladsl.settings.ClientConnectionSettings]].
   */
  def clientLayer(
    hostHeader: Host,
    settings:   ClientConnectionSettings,
    log:        LoggingAdapter           = system.log): ClientLayer =
    OutgoingConnectionBlueprint(hostHeader, settings, log)

  // ** CONNECTION POOL ** //

  /**
   * Starts a new connection pool to the given host and configuration and returns a [[akka.stream.scaladsl.Flow]] which dispatches
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
   * object of type `T` from the application which is emitted together with the corresponding response.
   *
   * To configure additional settings for the pool (and requests made using it),
   * use the `akka.http.host-connection-pool` config section or pass in a [[ConnectionPoolSettings]] explicitly.
   */
  def newHostConnectionPool[T](host: String, port: Int = 80,
                               settings: ConnectionPoolSettings = defaultConnectionPoolSettings,
                               log:      LoggingAdapter         = system.log)(implicit fm: Materializer): Flow[(HttpRequest, T), (Try[HttpResponse], T), HostConnectionPool] = {
    val cps = ConnectionPoolSetup(settings, ConnectionContext.noEncryption(), log)
    newHostConnectionPool(HostConnectionPoolSetup(host, port, cps))
  }

  /**
   * Same as [[#newHostConnectionPool]] but for encrypted (HTTPS) connections.
   *
   * If an explicit [[ConnectionContext]] is given then it rather than the configured default [[ConnectionContext]] will be used
   * for encryption on the connections.
   *
   * To configure additional settings for the pool (and requests made using it),
   * use the `akka.http.host-connection-pool` config section or pass in a [[ConnectionPoolSettings]] explicitly.
   */
  def newHostConnectionPoolHttps[T](host: String, port: Int = 443,
                                    connectionContext: HttpsConnectionContext = defaultClientHttpsContext,
                                    settings:          ConnectionPoolSettings = defaultConnectionPoolSettings,
                                    log:               LoggingAdapter         = system.log)(implicit fm: Materializer): Flow[(HttpRequest, T), (Try[HttpResponse], T), HostConnectionPool] = {
    val cps = ConnectionPoolSetup(settings, connectionContext, log)
    newHostConnectionPool(HostConnectionPoolSetup(host, port, cps))
  }

  /**
   * INTERNAL API
   *
   * Starts a new connection pool to the given host and configuration and returns a [[akka.stream.scaladsl.Flow]] which dispatches
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
   * object of type `T` from the application which is emitted together with the corresponding response.
   */
  private[akka] def newHostConnectionPool[T](setup: HostConnectionPoolSetup)(
    implicit
    fm: Materializer): Flow[(HttpRequest, T), (Try[HttpResponse], T), HostConnectionPool] = {
    val gateway = new PoolGateway(poolMasterActorRef, setup, PoolGateway.newUniqueGatewayIdentifier)
    gatewayClientFlow(setup, gateway.startPool())
  }

  /**
   * Returns a [[akka.stream.scaladsl.Flow]] which dispatches incoming HTTP requests to the per-ActorSystem pool of outgoing
   * HTTP connections to the given target host endpoint. For every ActorSystem, target host and pool
   * configuration a separate connection pool is maintained.
   * The HTTP layer transparently manages idle shutdown and restarting of connections pools as configured.
   * The returned [[akka.stream.scaladsl.Flow]] instances therefore remain valid throughout the lifetime of the application.
   *
   * The internal caching logic guarantees that there will never be more than a single pool running for the
   * given target host endpoint and configuration (in this ActorSystem).
   *
   * Since the underlying transport usually comprises more than a single connection the produced flow might generate
   * responses in an order that doesn't directly match the consumed requests.
   * For example, if two requests A and B enter the flow in that order the response for B might be produced before the
   * response for A.
   * In order to allow for easy response-to-request association the flow takes in a custom, opaque context
   * object of type `T` from the application which is emitted together with the corresponding response.
   *
   * To configure additional settings for the pool (and requests made using it),
   * use the `akka.http.host-connection-pool` config section or pass in a [[ConnectionPoolSettings]] explicitly.
   */
  def cachedHostConnectionPool[T](host: String, port: Int = 80,
                                  settings: ConnectionPoolSettings = defaultConnectionPoolSettings,
                                  log:      LoggingAdapter         = system.log)(implicit fm: Materializer): Flow[(HttpRequest, T), (Try[HttpResponse], T), HostConnectionPool] = {
    val cps = ConnectionPoolSetup(settings, ConnectionContext.noEncryption(), log)
    val setup = HostConnectionPoolSetup(host, port, cps)
    cachedHostConnectionPool(setup)
  }

  /**
   * Same as [[#cachedHostConnectionPool]] but for encrypted (HTTPS) connections.
   *
   * If an explicit [[ConnectionContext]] is given then it rather than the configured default [[ConnectionContext]] will be used
   * for encryption on the connections.
   *
   * To configure additional settings for the pool (and requests made using it),
   * use the `akka.http.host-connection-pool` config section or pass in a [[ConnectionPoolSettings]] explicitly.
   */
  def cachedHostConnectionPoolHttps[T](host: String, port: Int = 443,
                                       connectionContext: HttpsConnectionContext = defaultClientHttpsContext,
                                       settings:          ConnectionPoolSettings = defaultConnectionPoolSettings,
                                       log:               LoggingAdapter         = system.log)(implicit fm: Materializer): Flow[(HttpRequest, T), (Try[HttpResponse], T), HostConnectionPool] = {
    val cps = ConnectionPoolSetup(settings, connectionContext, log)
    val setup = HostConnectionPoolSetup(host, port, cps)
    cachedHostConnectionPool(setup)
  }

  /**
   * Returns a [[akka.stream.scaladsl.Flow]] which dispatches incoming HTTP requests to the per-ActorSystem pool of outgoing
   * HTTP connections to the given target host endpoint. For every ActorSystem, target host and pool
   * configuration a separate connection pool is maintained.
   * The HTTP layer transparently manages idle shutdown and restarting of connections pools as configured.
   * The returned [[akka.stream.scaladsl.Flow]] instances therefore remain valid throughout the lifetime of the application.
   *
   * The internal caching logic guarantees that there will never be more than a single pool running for the
   * given target host endpoint and configuration (in this ActorSystem).
   *
   * Since the underlying transport usually comprises more than a single connection the produced flow might generate
   * responses in an order that doesn't directly match the consumed requests.
   * For example, if two requests A and B enter the flow in that order the response for B might be produced before the
   * response for A.
   * In order to allow for easy response-to-request association the flow takes in a custom, opaque context
   * object of type `T` from the application which is emitted together with the corresponding response.
   */
  private def cachedHostConnectionPool[T](setup: HostConnectionPoolSetup)(
    implicit
    fm: Materializer): Flow[(HttpRequest, T), (Try[HttpResponse], T), HostConnectionPool] = {
    gatewayClientFlow(setup, sharedGateway(setup).startPool())
  }

  /**
   * Creates a new "super connection pool flow", which routes incoming requests to a (cached) host connection pool
   * depending on their respective effective URIs. Note that incoming requests must have an absolute URI.
   *
   * If an explicit [[ConnectionContext]] is given then it rather than the configured default [[ConnectionContext]] will be used
   * for setting up HTTPS connection pools, if required.
   *
   * Since the underlying transport usually comprises more than a single connection the produced flow might generate
   * responses in an order that doesn't directly match the consumed requests.
   * For example, if two requests A and B enter the flow in that order the response for B might be produced before the
   * response for A.
   * In order to allow for easy response-to-request association the flow takes in a custom, opaque context
   * object of type `T` from the application which is emitted together with the corresponding response.
   *
   * To configure additional settings for the pool (and requests made using it),
   * use the `akka.http.host-connection-pool` config section or pass in a [[ConnectionPoolSettings]] explicitly.
   */
  def superPool[T](
    connectionContext: HttpsConnectionContext = defaultClientHttpsContext,
    settings:          ConnectionPoolSettings = defaultConnectionPoolSettings,
    log:               LoggingAdapter         = system.log)(implicit fm: Materializer): Flow[(HttpRequest, T), (Try[HttpResponse], T), NotUsed] =
    clientFlow[T](settings) { request ⇒ request → sharedGateway(request, settings, connectionContext, log) }

  /**
   * Fires a single [[akka.http.scaladsl.model.HttpRequest]] across the (cached) host connection pool for the request's
   * effective URI to produce a response future.
   *
   * If an explicit [[ConnectionContext]] is given then it rather than the configured default [[ConnectionContext]] will be used
   * for setting up the HTTPS connection pool, if the request is targeted towards an `https` endpoint.
   *
   * Note that the request must have an absolute URI, otherwise the future will be completed with an error.
   */
  def singleRequest(
    request:           HttpRequest,
    connectionContext: HttpsConnectionContext = defaultClientHttpsContext,
    settings:          ConnectionPoolSettings = defaultConnectionPoolSettings,
    log:               LoggingAdapter         = system.log)(implicit fm: Materializer): Future[HttpResponse] =
    try {
      val gateway = sharedGateway(request, settings, connectionContext, log)
      gateway(request)
    } catch {
      case e: IllegalUriException ⇒ FastFuture.failed(e)
    }

  /**
   * Constructs a [[akka.http.scaladsl.Http.WebSocketClientLayer]] stage using the configured default [[akka.http.scaladsl.settings.ClientConnectionSettings]],
   * configured using the `akka.http.client` config section.
   *
   * The layer is not reusable and must only be materialized once.
   */
  def webSocketClientLayer(
    request:  WebSocketRequest,
    settings: ClientConnectionSettings = ClientConnectionSettings(system),
    log:      LoggingAdapter           = system.log): Http.WebSocketClientLayer =
    WebSocketClientBlueprint(request, settings, log)

  /**
   * Constructs a flow that once materialized establishes a WebSocket connection to the given Uri.
   *
   * The layer is not reusable and must only be materialized once.
   */
  def webSocketClientFlow(
    request:           WebSocketRequest,
    connectionContext: ConnectionContext         = defaultClientHttpsContext,
    localAddress:      Option[InetSocketAddress] = None,
    settings:          ClientConnectionSettings  = ClientConnectionSettings(system),
    log:               LoggingAdapter            = system.log): Flow[Message, Message, Future[WebSocketUpgradeResponse]] = {
    import request.uri
    require(uri.isAbsolute, s"WebSocket request URI must be absolute but was '$uri'")

    val ctx = uri.scheme match {
      case "ws"                                ⇒ ConnectionContext.noEncryption()
      case "wss" if connectionContext.isSecure ⇒ connectionContext
      case "wss"                               ⇒ throw new IllegalArgumentException("Provided connectionContext is not secure, yet request to secure `wss` endpoint detected!")
      case scheme ⇒
        throw new IllegalArgumentException(s"Illegal URI scheme '$scheme' in '$uri' for WebSocket request. " +
          s"WebSocket requests must use either 'ws' or 'wss'")
    }
    val host = uri.authority.host.address
    val port = uri.effectivePort

    webSocketClientLayer(request, settings, log)
      .joinMat(_outgoingTlsConnectionLayer(host, port, localAddress, settings, ctx, log))(Keep.left)
  }

  /**
   * Runs a single WebSocket conversation given a Uri and a flow that represents the client side of the
   * WebSocket conversation.
   */
  def singleWebSocketRequest[T](
    request:           WebSocketRequest,
    clientFlow:        Flow[Message, Message, T],
    connectionContext: ConnectionContext         = defaultClientHttpsContext,
    localAddress:      Option[InetSocketAddress] = None,
    settings:          ClientConnectionSettings  = ClientConnectionSettings(system),
    log:               LoggingAdapter            = system.log)(implicit mat: Materializer): (Future[WebSocketUpgradeResponse], T) =
    webSocketClientFlow(request, connectionContext, localAddress, settings, log)
      .joinMat(clientFlow)(Keep.both).run()

  /**
   * Triggers an orderly shutdown of all host connections pools currently maintained by the [[akka.actor.ActorSystem]].
   * The returned future is completed when all pools that were live at the time of this method call
   * have completed their shutdown process.
   *
   * If existing pool client flows are re-used or new ones materialized concurrently with or after this
   * method call the respective connection pools will be restarted and not contribute to the returned future.
   */
  def shutdownAllConnectionPools(): Future[Unit] = {
    val shutdownCompletedPromise = Promise[Done]()
    poolMasterActorRef ! ShutdownAll(shutdownCompletedPromise)
    shutdownCompletedPromise.future.map(_ ⇒ ())(system.dispatcher)
  }

  /**
   * Gets the current default server-side [[ConnectionContext]] – defaults to plain HTTP.
   * Can be modified using [[setDefaultServerHttpContext]], and will then apply for servers bound after that call has completed.
   */
  def defaultServerHttpContext: ConnectionContext =
    synchronized {
      if (_defaultServerConnectionContext == null)
        _defaultServerConnectionContext = ConnectionContext.noEncryption()
      _defaultServerConnectionContext
    }

  /**
   * Sets the default server-side [[ConnectionContext]].
   * If it is an instance of [[HttpsConnectionContext]] then the server will be bound using HTTPS.
   */
  def setDefaultServerHttpContext(context: ConnectionContext): Unit =
    synchronized {
      _defaultServerConnectionContext = context
    }

  /**
   * Gets the current default client-side [[HttpsConnectionContext]].
   * Defaults used here can be configured using ssl-config or the context can be replaced using [[setDefaultClientHttpsContext]]
   */
  def defaultClientHttpsContext: HttpsConnectionContext =
    synchronized {
      _defaultClientHttpsConnectionContext match {
        case null ⇒
          val ctx = createDefaultClientHttpsContext()
          _defaultClientHttpsConnectionContext = ctx
          ctx
        case ctx ⇒ ctx
      }
    }

  /**
   * Sets the default client-side [[HttpsConnectionContext]].
   */
  def setDefaultClientHttpsContext(context: HttpsConnectionContext): Unit =
    synchronized {
      _defaultClientHttpsConnectionContext = context
    }

  private def sharedGateway(request: HttpRequest, settings: ConnectionPoolSettings, connectionContext: ConnectionContext, log: LoggingAdapter): PoolGateway = {
    if (request.uri.scheme.nonEmpty && request.uri.authority.nonEmpty) {
      val httpsCtx = if (request.uri.scheme.equalsIgnoreCase("https")) connectionContext else ConnectionContext.noEncryption()
      val setup = ConnectionPoolSetup(settings, httpsCtx, log)
      val host = request.uri.authority.host.toString()
      val hcps = HostConnectionPoolSetup(host, request.uri.effectivePort, setup)
      sharedGateway(hcps)
    } else {
      val msg = s"Cannot determine request scheme and target endpoint as ${request.method} request to ${request.uri} doesn't have an absolute URI"
      throw new IllegalUriException(ErrorInfo(msg))
    }
  }

  private def sharedGateway(hcps: HostConnectionPoolSetup): PoolGateway =
    new PoolGateway(poolMasterActorRef, hcps, PoolGateway.SharedGateway)(systemMaterializer)

  private def gatewayClientFlow[T](
    hcps:    HostConnectionPoolSetup,
    gateway: PoolGateway)(
    implicit
    fm: Materializer): Flow[(HttpRequest, T), (Try[HttpResponse], T), HostConnectionPool] =
    clientFlow[T](hcps.setup.settings)(_ → gateway)
      .mapMaterializedValue(_ ⇒ HostConnectionPool(hcps)(gateway))

  private def clientFlow[T](settings: ConnectionPoolSettings)(f: HttpRequest ⇒ (HttpRequest, PoolGateway))(
    implicit
    system: ActorSystem, fm: Materializer): Flow[(HttpRequest, T), (Try[HttpResponse], T), NotUsed] = {
    // a connection pool can never have more than pipeliningLimit * maxConnections requests in flight at any point
    val parallelism = settings.pipeliningLimit * settings.maxConnections
    Flow[(HttpRequest, T)].mapAsyncUnordered(parallelism) {
      case (request, userContext) ⇒
        val (effectiveRequest, gateway) = f(request)
        val result = Promise[(Try[HttpResponse], T)]() // TODO: simplify to `transformWith` when on Scala 2.12
        gateway(effectiveRequest).onComplete(responseTry ⇒ result.success(responseTry → userContext))(fm.executionContext)
        result.future
    }
  }

  /** Creates real or placebo SslTls stage based on if ConnectionContext is HTTPS or not. */
  private[http] def sslTlsStage(connectionContext: ConnectionContext, role: TLSRole, hostInfo: Option[(String, Int)] = None) =
    connectionContext match {
      case hctx: HttpsConnectionContext ⇒ TLS(hctx.sslContext, connectionContext.sslConfig, hctx.firstSession, role, hostInfo = hostInfo)
      case other                        ⇒ TLSPlacebo() // if it's not HTTPS, we don't enable SSL/TLS
    }

  /**
   * INTERNAL API
   *
   * For testing only
   */
  private[scaladsl] def poolSize: Future[Int] = {
    val sizePromise = Promise[Int]()
    poolMasterActorRef ! PoolSize(sizePromise)
    sizePromise.future
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
  type ServerLayer = BidiFlow[HttpResponse, SslTlsOutbound, SslTlsInbound, HttpRequest, NotUsed]
  //#server-layer

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
  type ClientLayer = BidiFlow[HttpRequest, SslTlsOutbound, SslTlsInbound, HttpResponse, NotUsed]
  //#client-layer

  /**
   * The type of the client-side WebSocket layer as a stand-alone BidiFlow
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
  type WebSocketClientLayer = BidiFlow[Message, SslTlsOutbound, SslTlsInbound, Message, Future[WebSocketUpgradeResponse]]

  /**
   * Represents a prospective HTTP server binding.
   *
   * @param localAddress  The local address of the endpoint bound by the materialization of the `connections` [[akka.stream.scaladsl.Source]]
   *
   */
  final case class ServerBinding(localAddress: InetSocketAddress)(private val unbindAction: () ⇒ Future[Unit]) {

    /**
     * Asynchronously triggers the unbinding of the port that was bound by the materialization of the `connections`
     * [[akka.stream.scaladsl.Source]]
     *
     * The produced [[scala.concurrent.Future]] is fulfilled when the unbinding has been completed.
     */
    def unbind(): Future[Unit] = unbindAction()
  }

  /**
   * Represents one accepted incoming HTTP connection.
   */
  final case class IncomingConnection(
    localAddress:  InetSocketAddress,
    remoteAddress: InetSocketAddress,
    flow:          Flow[HttpResponse, HttpRequest, NotUsed]) {

    /**
     * Handles the connection with the given flow, which is materialized exactly once
     * and the respective materialization result returned.
     */
    def handleWith[Mat](handler: Flow[HttpRequest, HttpResponse, Mat])(implicit fm: Materializer): Mat =
      flow.joinMat(handler)(Keep.right).run()

    /**
     * Handles the connection with the given handler function.
     */
    def handleWithSyncHandler(handler: HttpRequest ⇒ HttpResponse)(implicit fm: Materializer): Unit =
      handleWith(Flow[HttpRequest].map(handler))

    /**
     * Handles the connection with the given handler function.
     */
    def handleWithAsyncHandler(handler: HttpRequest ⇒ Future[HttpResponse], parallelism: Int = 1)(implicit fm: Materializer): Unit =
      handleWith(Flow[HttpRequest].mapAsync(parallelism)(handler))
  }

  /**
   * Represents a prospective outgoing HTTP connection.
   */
  final case class OutgoingConnection(localAddress: InetSocketAddress, remoteAddress: InetSocketAddress)

  /**
   * Represents a connection pool to a specific target host and pool configuration.
   */
  final case class HostConnectionPool private[http] (setup: HostConnectionPoolSetup)(
    private[http] val gateway: PoolGateway) { // enable test access

    /**
     * Asynchronously triggers the shutdown of the host connection pool.
     *
     * The produced [[scala.concurrent.Future]] is fulfilled when the shutdown has been completed.
     */
    def shutdown()(implicit ec: ExecutionContextExecutor): Future[Done] = gateway.shutdown()

    private[http] def toJava = new akka.http.javadsl.HostConnectionPool {
      override def setup = HostConnectionPool.this.setup
      override def shutdown(executor: ExecutionContextExecutor): CompletionStage[Done] = HostConnectionPool.this.shutdown()(executor).toJava
    }
  }

  //////////////////// EXTENSION SETUP ///////////////////

  def apply()(implicit system: ActorSystem): HttpExt = super.apply(system)

  def lookup() = Http

  def createExtension(system: ExtendedActorSystem): HttpExt =
    new HttpExt(system.settings.config getConfig "akka.http")(system)
}

/**
 * TLS configuration for an HTTPS server binding or client connection.
 * For the sslContext please refer to the com.typeasfe.ssl-config library.
 * The remaining four parameters configure the initial session that will
 * be negotiated, see [[akka.stream.TLSProtocol.NegotiateNewSession]] for details.
 */
trait DefaultSSLContextCreation {

  protected def system: ActorSystem
  protected def sslConfig: AkkaSSLConfig

  // --- log warnings ---
  private[this] def log = system.log

  def validateAndWarnAboutLooseSettings() = {
    val WarningAboutGlobalLoose = "This is very dangerous and may expose you to man-in-the-middle attacks. " +
      "If you are forced to interact with a server that is behaving such that you must disable this setting, " +
      "please disable it for a given connection instead, by configuring a specific HttpsConnectionContext " +
      "for use only for the trusted target that hostname verification would have blocked."

    if (sslConfig.config.loose.disableHostnameVerification)
      log.warning("Detected that Hostname Verification is disabled globally (via ssl-config's akka.ssl-config.loose.disableHostnameVerification) for the Http extension! " +
        WarningAboutGlobalLoose)

    if (sslConfig.config.loose.disableSNI) {
      log.warning("Detected that Server Name Indication (SNI) is disabled globally (via ssl-config's akka.ssl-config.loose.disableSNI) for the Http extension! " +
        WarningAboutGlobalLoose)

    }
  }
  // --- end of log warnings ---

  def createDefaultClientHttpsContext(): HttpsConnectionContext =
    createClientHttpsContext(sslConfig)

  // currently the same configuration as client by default, however we should tune this for server-side apropriately (!)
  def createServerHttpsContext(sslConfig: AkkaSSLConfig): HttpsConnectionContext = {
    log.warning("Automatic server-side configuration is not supported yet, will attempt to use client-side settings. " +
      "Instead it is recommended to construct the Servers HttpsConnectionContext manually (via SSLContext).")
    createClientHttpsContext(sslConfig)
  }

  def createClientHttpsContext(sslConfig: AkkaSSLConfig): HttpsConnectionContext = {
    val config = sslConfig.config

    val log = Logging(system, getClass)
    val mkLogger = new AkkaLoggerFactory(system)

    // initial ssl context!
    val sslContext = if (sslConfig.config.default) {
      log.debug("buildSSLContext: ssl-config.default is true, using default SSLContext")
      sslConfig.validateDefaultTrustManager(config)
      SSLContext.getDefault
    } else {
      // break out the static methods as much as we can...
      val keyManagerFactory = sslConfig.buildKeyManagerFactory(config)
      val trustManagerFactory = sslConfig.buildTrustManagerFactory(config)
      new ConfigSSLContextBuilder(mkLogger, config, keyManagerFactory, trustManagerFactory).build()
    }

    // protocols!
    val defaultParams = sslContext.getDefaultSSLParameters
    val defaultProtocols = defaultParams.getProtocols
    val protocols = sslConfig.configureProtocols(defaultProtocols, config)
    defaultParams.setProtocols(protocols)

    // ciphers!
    val defaultCiphers = defaultParams.getCipherSuites
    val cipherSuites = sslConfig.configureCipherSuites(defaultCiphers, config)
    defaultParams.setCipherSuites(cipherSuites)

    // auth!
    import com.typesafe.sslconfig.ssl.{ ClientAuth ⇒ SslClientAuth }
    val clientAuth = config.sslParametersConfig.clientAuth match {
      case SslClientAuth.Default ⇒ None
      case SslClientAuth.Want    ⇒ Some(TLSClientAuth.Want)
      case SslClientAuth.Need    ⇒ Some(TLSClientAuth.Need)
      case SslClientAuth.None    ⇒ Some(TLSClientAuth.None)
    }

    // hostname!
    if (!sslConfig.config.loose.disableHostnameVerification) {
      defaultParams.setEndpointIdentificationAlgorithm("https")
    }

    new HttpsConnectionContext(sslContext, Some(sslConfig), Some(cipherSuites.toList), Some(defaultProtocols.toList), clientAuth, Some(defaultParams))
  }

}
