/*
 * Copyright (C) 2009-2015 Typesafe Inc. <http://www.typesafe.com>
 */

package akka.http.javadsl

import java.net.InetSocketAddress
import akka.http.impl.util.JavaMapping
import akka.http.javadsl.model.ws._
import akka.stream
import akka.stream.io.{ SslTlsInbound, SslTlsOutbound }

import scala.language.implicitConversions
import scala.concurrent.Future
import scala.util.Try
import akka.stream.scaladsl.Keep
import akka.japi.{ Pair, Option, Function }
import akka.actor.{ ExtendedActorSystem, ActorSystem, ExtensionIdProvider, ExtensionId }
import akka.event.LoggingAdapter
import akka.stream.Materializer
import akka.stream.javadsl.{ BidiFlow, Flow, Source }

import akka.http.impl.util.JavaMapping.Implicits._
import akka.http.scaladsl.{ model ⇒ sm }
import akka.http.javadsl.model._
import akka.http._

object Http extends ExtensionId[Http] with ExtensionIdProvider {
  override def get(system: ActorSystem): Http = super.get(system)
  def lookup() = Http
  def createExtension(system: ExtendedActorSystem): Http = new Http(system)
}

class Http(system: ExtendedActorSystem) extends akka.actor.Extension {
  import akka.dispatch.ExecutionContexts.{ sameThreadExecutionContext ⇒ ec }

  private lazy val delegate = akka.http.scaladsl.Http(system)

  private implicit def convertHttpsContext(hctx: Option[HttpsContext]) =
    hctx.map(_.asInstanceOf[akka.http.scaladsl.HttpsContext])

  /**
   * Constructs a server layer stage using the configured default [[ServerSettings]]. The returned [[BidiFlow]] isn't
   * reusable and can only be materialized once.
   */
  def serverLayer(materializer: Materializer): BidiFlow[HttpResponse, SslTlsOutbound, SslTlsInbound, HttpRequest, Unit] =
    adaptServerLayer(delegate.serverLayer()(materializer))

  /**
   * Constructs a server layer stage using the given [[ServerSettings]]. The returned [[BidiFlow]] isn't reusable and
   * can only be materialized once.
   */
  def serverLayer(settings: ServerSettings,
                  materializer: Materializer): BidiFlow[HttpResponse, SslTlsOutbound, SslTlsInbound, HttpRequest, Unit] =
    adaptServerLayer(delegate.serverLayer(settings)(materializer))

  /**
   * Constructs a server layer stage using the given [[ServerSettings]]. The returned [[BidiFlow]] isn't reusable and
   * can only be materialized once. The `remoteAddress`, if provided, will be added as a header to each [[HttpRequest]]
   * this layer produces if the `akka.http.server.remote-address-header` configuration option is enabled.
   */
  def serverLayer(settings: ServerSettings,
                  remoteAddress: Option[InetSocketAddress],
                  materializer: Materializer): BidiFlow[HttpResponse, SslTlsOutbound, SslTlsInbound, HttpRequest, Unit] =
    adaptServerLayer(delegate.serverLayer(settings, remoteAddress.asScala)(materializer))

  /**
   * Constructs a server layer stage using the given [[ServerSettings]]. The returned [[BidiFlow]] isn't reusable and
   * can only be materialized once. The remoteAddress, if provided, will be added as a header to each [[HttpRequest]]
   * this layer produces if the `akka.http.server.remote-address-header` configuration option is enabled.
   */
  def serverLayer(settings: ServerSettings,
                  remoteAddress: Option[InetSocketAddress],
                  log: LoggingAdapter,
                  materializer: Materializer): BidiFlow[HttpResponse, SslTlsOutbound, SslTlsInbound, HttpRequest, Unit] =
    adaptServerLayer(delegate.serverLayer(settings, remoteAddress.asScala, log)(materializer))

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
  def bind(interface: String, port: Int, materializer: Materializer): Source[IncomingConnection, Future[ServerBinding]] =
    new Source(delegate.bind(interface, port)(materializer)
      .map(new IncomingConnection(_))
      .mapMaterializedValue(_.map(new ServerBinding(_))(ec)))

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
  def bind(interface: String, port: Int,
           settings: ServerSettings,
           httpsContext: Option[HttpsContext],
           log: LoggingAdapter,
           materializer: Materializer): Source[IncomingConnection, Future[ServerBinding]] =
    new Source(delegate.bind(interface, port, settings, httpsContext, log)(materializer)
      .map(new IncomingConnection(_))
      .mapMaterializedValue(_.map(new ServerBinding(_))(ec)))

  /**
   * Convenience method which starts a new HTTP server at the given endpoint and uses the given ``handler``
   * [[Flow]] for processing all incoming connections.
   *
   * The number of concurrently accepted connections can be configured by overriding
   * the `akka.http.server.max-connections` setting.
   */
  def bindAndHandle(handler: Flow[HttpRequest, HttpResponse, _],
                    interface: String, port: Int,
                    materializer: Materializer): Future[ServerBinding] =
    delegate.bindAndHandle(handler.asInstanceOf[Flow[sm.HttpRequest, sm.HttpResponse, _]].asScala,
      interface, port)(materializer)
      .map(new ServerBinding(_))(ec)

  /**
   * Convenience method which starts a new HTTP server at the given endpoint and uses the given ``handler``
   * [[Flow]] for processing all incoming connections.
   *
   * The number of concurrently accepted connections can be configured by overriding
   * the `akka.http.server.max-connections` setting.
   */
  def bindAndHandle(handler: Flow[HttpRequest, HttpResponse, _],
                    interface: String, port: Int,
                    settings: ServerSettings,
                    httpsContext: Option[HttpsContext],
                    log: LoggingAdapter,
                    materializer: Materializer): Future[ServerBinding] =
    delegate.bindAndHandle(handler.asInstanceOf[Flow[sm.HttpRequest, sm.HttpResponse, _]].asScala,
      interface, port, settings, httpsContext, log)(materializer)
      .map(new ServerBinding(_))(ec)

  /**
   * Convenience method which starts a new HTTP server at the given endpoint and uses the given ``handler``
   * [[Flow]] for processing all incoming connections.
   *
   * The number of concurrently accepted connections can be configured by overriding
   * the `akka.http.server.max-connections` setting.
   */
  def bindAndHandleSync(handler: Function[HttpRequest, HttpResponse],
                        interface: String, port: Int,
                        materializer: Materializer): Future[ServerBinding] =
    delegate.bindAndHandleSync(handler.apply(_).asScala, interface, port)(materializer)
      .map(new ServerBinding(_))(ec)

  /**
   * Convenience method which starts a new HTTP server at the given endpoint and uses the given ``handler``
   * [[Flow]] for processing all incoming connections.
   *
   * The number of concurrently accepted connections can be configured by overriding
   * the `akka.http.server.max-connections` setting.
   */
  def bindAndHandleSync(handler: Function[HttpRequest, HttpResponse],
                        interface: String, port: Int,
                        settings: ServerSettings,
                        httpsContext: Option[HttpsContext],
                        log: LoggingAdapter,
                        materializer: Materializer): Future[ServerBinding] =
    delegate.bindAndHandleSync(handler.apply(_).asScala,
      interface, port, settings, httpsContext, log)(materializer)
      .map(new ServerBinding(_))(ec)

  /**
   * Convenience method which starts a new HTTP server at the given endpoint and uses the given ``handler``
   * [[Flow]] for processing all incoming connections.
   *
   * The number of concurrently accepted connections can be configured by overriding
   * the `akka.http.server.max-connections` setting.
   */
  def bindAndHandleAsync(handler: Function[HttpRequest, Future[HttpResponse]],
                         interface: String, port: Int,
                         materializer: Materializer): Future[ServerBinding] =
    delegate.bindAndHandleAsync(handler.apply(_).asInstanceOf[Future[sm.HttpResponse]], interface, port)(materializer)
      .map(new ServerBinding(_))(ec)

  /**
   * Convenience method which starts a new HTTP server at the given endpoint and uses the given ``handler``
   * [[Flow]] for processing all incoming connections.
   *
   * The number of concurrently accepted connections can be configured by overriding
   * the `akka.http.server.max-connections` setting.
   */
  def bindAndHandleAsync(handler: Function[HttpRequest, Future[HttpResponse]],
                         interface: String, port: Int,
                         settings: ServerSettings, httpsContext: Option[HttpsContext],
                         parallelism: Int, log: LoggingAdapter,
                         materializer: Materializer): Future[ServerBinding] =
    delegate.bindAndHandleAsync(handler.apply(_).asInstanceOf[Future[sm.HttpResponse]],
      interface, port, settings, httpsContext, parallelism, log)(materializer)
      .map(new ServerBinding(_))(ec)

  /**
   * Constructs a client layer stage using the configured default [[ClientConnectionSettings]].
   */
  def clientLayer(hostHeader: headers.Host): BidiFlow[HttpRequest, SslTlsOutbound, SslTlsInbound, HttpResponse, Unit] =
    adaptClientLayer(delegate.clientLayer(JavaMapping.toScala(hostHeader)))

  /**
   * Constructs a client layer stage using the given [[ClientConnectionSettings]].
   */
  def clientLayer(hostHeader: headers.Host,
                  settings: ClientConnectionSettings): BidiFlow[HttpRequest, SslTlsOutbound, SslTlsInbound, HttpResponse, Unit] =
    adaptClientLayer(delegate.clientLayer(JavaMapping.toScala(hostHeader), settings))

  /**
   * Constructs a client layer stage using the given [[ClientConnectionSettings]].
   */
  def clientLayer(hostHeader: headers.Host,
                  settings: ClientConnectionSettings,
                  log: LoggingAdapter): BidiFlow[HttpRequest, SslTlsOutbound, SslTlsInbound, HttpResponse, Unit] =
    adaptClientLayer(delegate.clientLayer(JavaMapping.toScala(hostHeader), settings, log))

  /**
   * Creates a [[Flow]] representing a prospective HTTP client connection to the given endpoint.
   * Every materialization of the produced flow will attempt to establish a new outgoing connection.
   */
  def outgoingConnection(host: String): Flow[HttpRequest, HttpResponse, Future[OutgoingConnection]] =
    outgoingConnection(host, 80)

  /**
   * Same as [[outgoingConnection]] but with HTTPS encryption.
   */
  def outgoingConnectionTls(host: String): Flow[HttpRequest, HttpResponse, Future[OutgoingConnection]] =
    outgoingConnectionTls(host, 443)

  /**
   * Creates a [[Flow]] representing a prospective HTTP client connection to the given endpoint.
   * Every materialization of the produced flow will attempt to establish a new outgoing connection.
   */
  def outgoingConnection(host: String, port: Int): Flow[HttpRequest, HttpResponse, Future[OutgoingConnection]] =
    Flow.fromGraph {
      akka.stream.scaladsl.Flow[HttpRequest].map(_.asScala)
        .viaMat(delegate.outgoingConnection(host, port))(Keep.right)
        .mapMaterializedValue(_.map(new OutgoingConnection(_))(ec))
    }

  /**
   * Same as [[outgoingConnection]] but with HTTPS encryption.
   */
  def outgoingConnectionTls(host: String, port: Int): Flow[HttpRequest, HttpResponse, Future[OutgoingConnection]] =
    Flow.fromGraph {
      akka.stream.scaladsl.Flow[HttpRequest].map(_.asScala)
        .viaMat(delegate.outgoingConnectionTls(host, port))(Keep.right)
        .mapMaterializedValue(_.map(new OutgoingConnection(_))(ec))
    }

  /**
   * Creates a [[Flow]] representing a prospective HTTP client connection to the given endpoint.
   * Every materialization of the produced flow will attempt to establish a new outgoing connection.
   */
  def outgoingConnection(host: String, port: Int,
                         localAddress: Option[InetSocketAddress],
                         settings: ClientConnectionSettings,
                         log: LoggingAdapter): Flow[HttpRequest, HttpResponse, Future[OutgoingConnection]] =
    Flow.fromGraph {
      akka.stream.scaladsl.Flow[HttpRequest].map(_.asScala)
        .viaMat(delegate.outgoingConnection(host, port, localAddress.asScala, settings, log))(Keep.right)
        .mapMaterializedValue(_.map(new OutgoingConnection(_))(ec))
    }

  /**
   * Same as [[outgoingConnection]] but with HTTPS encryption.
   *
   * If an explicit [[HttpsContext]] is given then it rather than the configured default [[HttpsContext]] will be used
   * for encryption on the connection.
   */
  def outgoingConnectionTls(host: String, port: Int,
                            localAddress: Option[InetSocketAddress],
                            settings: ClientConnectionSettings,
                            httpsContext: Option[HttpsContext],
                            log: LoggingAdapter): Flow[HttpRequest, HttpResponse, Future[OutgoingConnection]] =
    Flow.fromGraph {
      akka.stream.scaladsl.Flow[HttpRequest].map(_.asScala)
        .viaMat(delegate.outgoingConnectionTls(host, port, localAddress.asScala, settings,
          httpsContext.map(_.asInstanceOf[akka.http.scaladsl.HttpsContext]), log))(Keep.right)
        .mapMaterializedValue(_.map(new OutgoingConnection(_))(ec))
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
  def newHostConnectionPool[T](host: String, port: Int, materializer: Materializer): Flow[Pair[HttpRequest, T], Pair[Try[HttpResponse], T], HostConnectionPool] =
    adaptTupleFlow(delegate.newHostConnectionPool[T](host, port)(materializer))

  /**
   * Same as [[newHostConnectionPool]] but with HTTPS encryption.
   */
  def newHostConnectionPoolTls[T](host: String, port: Int, materializer: Materializer): Flow[Pair[HttpRequest, T], Pair[Try[HttpResponse], T], HostConnectionPool] =
    adaptTupleFlow(delegate.newHostConnectionPoolTls[T](host, port)(materializer))

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
  def newHostConnectionPool[T](host: String, port: Int,
                               settings: ConnectionPoolSettings,
                               log: LoggingAdapter, materializer: Materializer): Flow[Pair[HttpRequest, T], Pair[Try[HttpResponse], T], HostConnectionPool] =
    adaptTupleFlow(delegate.newHostConnectionPool[T](host, port, settings, log)(materializer))

  /**
   * Same as [[newHostConnectionPool]] but with HTTPS encryption.
   *
   * If an explicit [[HttpsContext]] is given then it rather than the configured default [[HttpsContext]] will be used
   * for encryption on the connection.
   */
  def newHostConnectionPoolTls[T](host: String, port: Int,
                                  settings: ConnectionPoolSettings,
                                  httpsContext: Option[HttpsContext],
                                  log: LoggingAdapter, materializer: Materializer): Flow[Pair[HttpRequest, T], Pair[Try[HttpResponse], T], HostConnectionPool] =
    adaptTupleFlow(delegate.newHostConnectionPoolTls[T](host, port, settings,
      httpsContext.map(_.asInstanceOf[akka.http.scaladsl.HttpsContext]), log)(materializer))

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
  def newHostConnectionPool[T](setup: HostConnectionPoolSetup, materializer: Materializer): Flow[Pair[HttpRequest, T], Pair[Try[HttpResponse], T], HostConnectionPool] =
    adaptTupleFlow(delegate.newHostConnectionPool[T](setup)(materializer))

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
  def cachedHostConnectionPool[T](host: String, port: Int, materializer: Materializer): Flow[Pair[HttpRequest, T], Pair[Try[HttpResponse], T], HostConnectionPool] =
    adaptTupleFlow(delegate.cachedHostConnectionPool[T](host, port)(materializer))

  /**
   * Same as [[cachedHostConnectionPool]] but with HTTPS encryption.
   */
  def cachedHostConnectionPoolTls[T](host: String, port: Int, materializer: Materializer): Flow[Pair[HttpRequest, T], Pair[Try[HttpResponse], T], HostConnectionPool] =
    adaptTupleFlow(delegate.cachedHostConnectionPoolTls[T](host, port)(materializer))

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
  def cachedHostConnectionPool[T](host: String, port: Int,
                                  settings: ConnectionPoolSettings,
                                  log: LoggingAdapter, materializer: Materializer): Flow[Pair[HttpRequest, T], Pair[Try[HttpResponse], T], HostConnectionPool] =
    adaptTupleFlow(delegate.cachedHostConnectionPool[T](host, port, settings, log)(materializer))

  /**
   * Same as [[cachedHostConnectionPool]] but with HTTPS encryption.
   *
   * If an explicit [[HttpsContext]] is given then it rather than the configured default [[HttpsContext]] will be used
   * for encryption on the connection.
   */
  def cachedHostConnectionPoolTls[T](host: String, port: Int,
                                     settings: ConnectionPoolSettings,
                                     httpsContext: Option[HttpsContext],
                                     log: LoggingAdapter, materializer: Materializer): Flow[Pair[HttpRequest, T], Pair[Try[HttpResponse], T], HostConnectionPool] =
    adaptTupleFlow(delegate.cachedHostConnectionPoolTls[T](host, port, settings,
      httpsContext.map(_.asInstanceOf[akka.http.scaladsl.HttpsContext]), log)(materializer))

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
  def cachedHostConnectionPool[T](setup: HostConnectionPoolSetup, materializer: Materializer): Flow[Pair[HttpRequest, T], Pair[Try[HttpResponse], T], HostConnectionPool] =
    adaptTupleFlow(delegate.cachedHostConnectionPool[T](setup)(materializer))

  /**
   * Creates a new "super connection pool flow", which routes incoming requests to a (cached) host connection pool
   * depending on their respective effective URIs. Note that incoming requests must have either an absolute URI or
   * a valid `Host` header.
   *
   * Since the underlying transport usually comprises more than a single connection the produced flow might generate
   * responses in an order that doesn't directly match the consumed requests.
   * For example, if two requests A and B enter the flow in that order the response for B might be produced before the
   * response for A.
   * In order to allow for easy response-to-request association the flow takes in a custom, opaque context
   * object of type ``T`` from the application which is emitted together with the corresponding response.
   */
  def superPool[T](materializer: Materializer): Flow[Pair[HttpRequest, T], Pair[Try[HttpResponse], T], Unit] =
    adaptTupleFlow(delegate.superPool[T]()(materializer))

  /**
   * Creates a new "super connection pool flow", which routes incoming requests to a (cached) host connection pool
   * depending on their respective effective URIs. Note that incoming requests must have either an absolute URI or
   * a valid `Host` header.
   *
   * If an explicit [[HttpsContext]] is given then it rather than the configured default [[HttpsContext]] will be used
   * for setting up the HTTPS connection pool, if required.
   *
   * Since the underlying transport usually comprises more than a single connection the produced flow might generate
   * responses in an order that doesn't directly match the consumed requests.
   * For example, if two requests A and B enter the flow in that order the response for B might be produced before the
   * response for A.
   * In order to allow for easy response-to-request association the flow takes in a custom, opaque context
   * object of type ``T`` from the application which is emitted together with the corresponding response.
   */
  def superPool[T](settings: ConnectionPoolSettings,
                   httpsContext: Option[HttpsContext],
                   log: LoggingAdapter, materializer: Materializer): Flow[Pair[HttpRequest, T], Pair[Try[HttpResponse], T], Unit] =
    adaptTupleFlow(delegate.superPool[T](settings, httpsContext, log)(materializer))

  /**
   * Fires a single [[HttpRequest]] across the (cached) host connection pool for the request's
   * effective URI to produce a response future.
   *
   * Note that the request must have either an absolute URI or a valid `Host` header, otherwise
   * the future will be completed with an error.
   */
  def singleRequest(request: HttpRequest, materializer: Materializer): Future[HttpResponse] =
    delegate.singleRequest(request.asScala)(materializer)

  /**
   * Fires a single [[HttpRequest]] across the (cached) host connection pool for the request's
   * effective URI to produce a response future.
   *
   * If an explicit [[HttpsContext]] is given then it rather than the configured default [[HttpsContext]] will be used
   * for setting up the HTTPS connection pool, if required.
   *
   * Note that the request must have either an absolute URI or a valid `Host` header, otherwise
   * the future will be completed with an error.
   */
  def singleRequest(request: HttpRequest,
                    settings: ConnectionPoolSettings,
                    httpsContext: Option[HttpsContext],
                    log: LoggingAdapter, materializer: Materializer): Future[HttpResponse] =
    delegate.singleRequest(request.asScala, settings, httpsContext, log)(materializer)

  /**
   * Constructs a Websocket [[BidiFlow]].
   *
   * The layer is not reusable and must only be materialized once.
   */
  def websocketClientLayer(request: WebsocketRequest): BidiFlow[Message, SslTlsOutbound, SslTlsInbound, Message, Future[WebsocketUpgradeResponse]] =
    adaptWsBidiFlow(delegate.websocketClientLayer(request.asScala))

  /**
   * Constructs a Websocket [[BidiFlow]] using the configured default [[ClientConnectionSettings]],
   * configured using the `akka.http.client` config section.
   *
   * The layer is not reusable and must only be materialized once.
   */
  def websocketClientLayer(request: WebsocketRequest,
                           settings: ClientConnectionSettings): BidiFlow[Message, SslTlsOutbound, SslTlsInbound, Message, Future[WebsocketUpgradeResponse]] =
    adaptWsBidiFlow(delegate.websocketClientLayer(request.asScala, settings))

  /**
   * Constructs a Websocket [[BidiFlow]] using the configured default [[ClientConnectionSettings]],
   * configured using the `akka.http.client` config section.
   *
   * The layer is not reusable and must only be materialized once.
   */
  def websocketClientLayer(request: WebsocketRequest,
                           settings: ClientConnectionSettings,
                           log: LoggingAdapter): BidiFlow[Message, SslTlsOutbound, SslTlsInbound, Message, Future[WebsocketUpgradeResponse]] =
    adaptWsBidiFlow(delegate.websocketClientLayer(request.asScala, settings, log))

  /**
   * Constructs a flow that once materialized establishes a Websocket connection to the given Uri.
   *
   * The layer is not reusable and must only be materialized once.
   */
  def websocketClientFlow(request: WebsocketRequest): Flow[Message, Message, Future[WebsocketUpgradeResponse]] =
    adaptWsFlow {
      delegate.websocketClientFlow(request.asScala)
    }

  /**
   * Constructs a flow that once materialized establishes a Websocket connection to the given Uri.
   *
   * The layer is not reusable and must only be materialized once.
   */
  def websocketClientFlow(request: WebsocketRequest,
                          localAddress: Option[InetSocketAddress],
                          settings: ClientConnectionSettings,
                          httpsContext: Option[HttpsContext],
                          log: LoggingAdapter): Flow[Message, Message, Future[WebsocketUpgradeResponse]] =
    adaptWsFlow {
      delegate.websocketClientFlow(request.asScala, localAddress, settings, httpsContext, log)
    }

  /**
   * Runs a single Websocket conversation given a Uri and a flow that represents the client side of the
   * Websocket conversation.
   */
  def singleWebsocketRequest[T](request: WebsocketRequest,
                                clientFlow: Flow[Message, Message, T],
                                materializer: Materializer): Pair[Future[WebsocketUpgradeResponse], T] =
    adaptWsResultTuple {
      delegate.singleWebsocketRequest(
        request.asScala,
        adaptWsFlow[T](clientFlow))(materializer)
    }

  /**
   * Runs a single Websocket conversation given a Uri and a flow that represents the client side of the
   * Websocket conversation.
   */
  def singleWebsocketRequest[T](request: WebsocketRequest,
                                clientFlow: Flow[Message, Message, T],
                                localAddress: Option[InetSocketAddress],
                                settings: ClientConnectionSettings,
                                httpsContext: Option[HttpsContext],
                                log: LoggingAdapter,
                                materializer: Materializer): Pair[Future[WebsocketUpgradeResponse], T] =
    adaptWsResultTuple {
      delegate.singleWebsocketRequest(
        request.asScala,
        adaptWsFlow[T](clientFlow),
        localAddress,
        settings,
        httpsContext,
        log)(materializer)
    }

  /**
   * Triggers an orderly shutdown of all host connections pools currently maintained by the [[ActorSystem]].
   * The returned future is completed when all pools that were live at the time of this method call
   * have completed their shutdown process.
   *
   * If existing pool client flows are re-used or new ones materialized concurrently with or after this
   * method call the respective connection pools will be restarted and not contribute to the returned future.
   */
  def shutdownAllConnectionPools(): Future[Unit] = delegate.shutdownAllConnectionPools()

  /**
   * Gets the current default client-side [[HttpsContext]].
   */
  def defaultClientHttpsContext: HttpsContext = delegate.defaultClientHttpsContext

  /**
   * Sets the default client-side [[HttpsContext]].
   */
  def setDefaultClientHttpsContext(context: HttpsContext): Unit =
    delegate.setDefaultClientHttpsContext(context.asInstanceOf[akka.http.scaladsl.HttpsContext])

  private def adaptTupleFlow[T, Mat](scalaFlow: stream.scaladsl.Flow[(scaladsl.model.HttpRequest, T), (Try[scaladsl.model.HttpResponse], T), Mat]): Flow[Pair[HttpRequest, T], Pair[Try[HttpResponse], T], Mat] = {
    implicit val _ = JavaMapping.identity[T]
    JavaMapping.toJava(scalaFlow)(JavaMapping.flowMapping[Pair[HttpRequest, T], (scaladsl.model.HttpRequest, T), Pair[Try[HttpResponse], T], (Try[scaladsl.model.HttpResponse], T), Mat])
  }

  private def adaptServerLayer(serverLayer: scaladsl.Http.ServerLayer): BidiFlow[HttpResponse, SslTlsOutbound, SslTlsInbound, HttpRequest, Unit] =
    new BidiFlow(
      JavaMapping.adapterBidiFlow[HttpResponse, sm.HttpResponse, sm.HttpRequest, HttpRequest]
        .atop(serverLayer))

  private def adaptClientLayer(clientLayer: scaladsl.Http.ClientLayer): BidiFlow[HttpRequest, SslTlsOutbound, SslTlsInbound, HttpResponse, Unit] =
    new BidiFlow(
      JavaMapping.adapterBidiFlow[HttpRequest, sm.HttpRequest, sm.HttpResponse, HttpResponse]
        .atop(clientLayer))

  private def adaptWsBidiFlow(wsLayer: scaladsl.Http.WebsocketClientLayer): BidiFlow[Message, SslTlsOutbound, SslTlsInbound, Message, Future[WebsocketUpgradeResponse]] =
    new BidiFlow(
      JavaMapping.adapterBidiFlow[Message, sm.ws.Message, sm.ws.Message, Message]
        .atopMat(wsLayer)((_, s) ⇒ adaptWsUpgradeResponse(s)))

  private def adaptWsFlow(wsLayer: stream.scaladsl.Flow[sm.ws.Message, sm.ws.Message, Future[scaladsl.model.ws.WebsocketUpgradeResponse]]): Flow[Message, Message, Future[WebsocketUpgradeResponse]] =
    Flow.fromGraph(JavaMapping.adapterBidiFlow[Message, sm.ws.Message, sm.ws.Message, Message].joinMat(wsLayer)(Keep.right).mapMaterializedValue(adaptWsUpgradeResponse _))

  private def adaptWsFlow[Mat](javaFlow: Flow[Message, Message, Mat]): stream.scaladsl.Flow[scaladsl.model.ws.Message, scaladsl.model.ws.Message, Mat] =
    stream.scaladsl.Flow[scaladsl.model.ws.Message]
      .map(Message.adapt)
      .viaMat(javaFlow.asScala)(Keep.right)
      .map(_.asScala)

  private def adaptWsResultTuple[T](result: (Future[scaladsl.model.ws.WebsocketUpgradeResponse], T)): Pair[Future[WebsocketUpgradeResponse], T] =
    result match {
      case (fut, tMat) ⇒ Pair(adaptWsUpgradeResponse(fut), tMat)
    }
  private def adaptWsUpgradeResponse(responseFuture: Future[scaladsl.model.ws.WebsocketUpgradeResponse]): Future[WebsocketUpgradeResponse] =
    responseFuture.map(WebsocketUpgradeResponse.adapt)(system.dispatcher)
}
