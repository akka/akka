/*
 * Copyright (C) 2009-2015 Typesafe Inc. <http://www.typesafe.com>
 */

package akka.http.javadsl

import java.lang.{ Iterable ⇒ JIterable }
import akka.http.ServerSettings

import scala.concurrent.Future
import akka.japi.Util._
import akka.japi.Function
import akka.actor.{ ExtendedActorSystem, ActorSystem, ExtensionIdProvider, ExtensionId }
import akka.event.LoggingAdapter
import akka.io.Inet
import akka.stream.FlowMaterializer
import akka.stream.javadsl.{ Flow, Source }
import akka.http.scaladsl.{ model ⇒ sm }
import akka.http.javadsl.model._

object Http extends ExtensionId[Http] with ExtensionIdProvider {
  override def get(system: ActorSystem): Http = super.get(system)
  def lookup() = Http
  def createExtension(system: ExtendedActorSystem): Http = new Http(system)
}

class Http(system: ExtendedActorSystem) extends akka.actor.Extension {
  import akka.dispatch.ExecutionContexts.{ sameThreadExecutionContext ⇒ ec }

  private lazy val delegate = akka.http.scaladsl.Http(system)

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
  def bind(interface: String, port: Int, fm: FlowMaterializer): Source[IncomingConnection, Future[ServerBinding]] =
    Source.adapt(delegate.bind(interface, port)(fm)
      .map(new IncomingConnection(_))
      .mapMaterialized(_.map(new ServerBinding(_))(ec)))

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
           backlog: Int, options: JIterable[Inet.SocketOption],
           settings: ServerSettings,
           log: LoggingAdapter,
           fm: FlowMaterializer): Source[IncomingConnection, Future[ServerBinding]] =
    Source.adapt(delegate.bind(interface, port, backlog, immutableSeq(options), settings, log)(fm)
      .map(new IncomingConnection(_))
      .mapMaterialized(_.map(new ServerBinding(_))(ec)))

  /**
   * Convenience method which starts a new HTTP server at the given endpoint and uses the given ``handler``
   * [[Flow]] for processing all incoming connections.
   *
   * Note that there is no backpressure being applied to the `connections` [[Source]], i.e. all
   * connections are being accepted at maximum rate, which, depending on the applications, might
   * present a DoS risk!
   */
  def bindAndHandle(handler: Flow[HttpRequest, HttpResponse, _],
                    interface: String, port: Int,
                    fm: FlowMaterializer): Future[ServerBinding] =
    delegate.bindAndHandle(handler.asInstanceOf[Flow[sm.HttpRequest, sm.HttpResponse, _]].asScala,
      interface, port)(fm)
      .map(new ServerBinding(_))(ec)

  /**
   * Convenience method which starts a new HTTP server at the given endpoint and uses the given ``handler``
   * [[Flow]] for processing all incoming connections.
   *
   * Note that there is no backpressure being applied to the `connections` [[Source]], i.e. all
   * connections are being accepted at maximum rate, which, depending on the applications, might
   * present a DoS risk!
   */
  def bindAndHandle(handler: Flow[HttpRequest, HttpResponse, _],
                    interface: String, port: Int,
                    backlog: Int, options: JIterable[Inet.SocketOption],
                    settings: ServerSettings,
                    log: LoggingAdapter,
                    fm: FlowMaterializer): Future[ServerBinding] =
    delegate.bindAndHandle(handler.asInstanceOf[Flow[sm.HttpRequest, sm.HttpResponse, _]].asScala,
      interface, port, backlog, immutableSeq(options), settings, log)(fm)
      .map(new ServerBinding(_))(ec)

  /**
   * Convenience method which starts a new HTTP server at the given endpoint and uses the given ``handler``
   * [[Flow]] for processing all incoming connections.
   *
   * Note that there is no backpressure being applied to the `connections` [[Source]], i.e. all
   * connections are being accepted at maximum rate, which, depending on the applications, might
   * present a DoS risk!
   */
  def bindAndHandleSync(handler: Function[HttpRequest, HttpResponse],
                        interface: String, port: Int,
                        fm: FlowMaterializer): Future[ServerBinding] =
    delegate.bindAndHandleSync(handler.apply(_).asInstanceOf[sm.HttpResponse], interface, port)(fm)
      .map(new ServerBinding(_))(ec)

  /**
   * Convenience method which starts a new HTTP server at the given endpoint and uses the given ``handler``
   * [[Flow]] for processing all incoming connections.
   *
   * Note that there is no backpressure being applied to the `connections` [[Source]], i.e. all
   * connections are being accepted at maximum rate, which, depending on the applications, might
   * present a DoS risk!
   */
  def bindAndHandleSync(handler: Function[HttpRequest, HttpResponse],
                        interface: String, port: Int,
                        backlog: Int, options: JIterable[Inet.SocketOption],
                        settings: ServerSettings,
                        log: LoggingAdapter,
                        fm: FlowMaterializer): Future[ServerBinding] =
    delegate.bindAndHandleSync(handler.apply(_).asInstanceOf[sm.HttpResponse],
      interface, port, backlog, immutableSeq(options), settings, log)(fm)
      .map(new ServerBinding(_))(ec)

  /**
   * Convenience method which starts a new HTTP server at the given endpoint and uses the given ``handler``
   * [[Flow]] for processing all incoming connections.
   *
   * Note that there is no backpressure being applied to the `connections` [[Source]], i.e. all
   * connections are being accepted at maximum rate, which, depending on the applications, might
   * present a DoS risk!
   */
  def bindAndHandleAsync(handler: Function[HttpRequest, Future[HttpResponse]],
                         interface: String, port: Int,
                         fm: FlowMaterializer): Future[ServerBinding] =
    delegate.bindAndHandleAsync(handler.apply(_).asInstanceOf[Future[sm.HttpResponse]], interface, port)(fm)
      .map(new ServerBinding(_))(ec)

  /**
   * Convenience method which starts a new HTTP server at the given endpoint and uses the given ``handler``
   * [[Flow]] for processing all incoming connections.
   *
   * Note that there is no backpressure being applied to the `connections` [[Source]], i.e. all
   * connections are being accepted at maximum rate, which, depending on the applications, might
   * present a DoS risk!
   */
  def bindAndHandleAsync(handler: Function[HttpRequest, Future[HttpResponse]],
                         interface: String, port: Int,
                         backlog: Int, options: JIterable[Inet.SocketOption],
                         settings: ServerSettings,
                         log: LoggingAdapter,
                         fm: FlowMaterializer): Future[ServerBinding] =
    delegate.bindAndHandleAsync(handler.apply(_).asInstanceOf[Future[sm.HttpResponse]],
      interface, port, backlog, immutableSeq(options), settings, log)(fm)
      .map(new ServerBinding(_))(ec)
}
