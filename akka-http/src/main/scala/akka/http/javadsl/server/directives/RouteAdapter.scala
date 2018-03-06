/*
 * Copyright (C) 2009-2018 Lightbend Inc. <https://www.lightbend.com>
 */

package akka.http.javadsl.server.directives

import akka.NotUsed
import akka.actor.ActorSystem
import akka.http.javadsl.model.HttpRequest
import akka.http.javadsl.model.HttpResponse
import akka.http.impl.util.JavaMapping.Implicits._
import akka.http.javadsl.server.{ ExceptionHandler, RejectionHandler, Route, RoutingJavaMapping }
import RoutingJavaMapping._
import akka.annotation.InternalApi
import akka.http.javadsl.settings.{ ParserSettings, RoutingSettings }
import akka.http.scaladsl
import akka.http.scaladsl.server.RouteConcatenation._
import akka.stream.{ Materializer, javadsl }
import akka.stream.scaladsl.Flow

/** INTERNAL API */
@InternalApi
final class RouteAdapter(val delegate: akka.http.scaladsl.server.Route) extends Route {

  override def flow(system: ActorSystem, materializer: Materializer): javadsl.Flow[HttpRequest, HttpResponse, NotUsed] =
    scalaFlow(system, materializer).asJava

  private def scalaFlow(system: ActorSystem, materializer: Materializer): Flow[HttpRequest, HttpResponse, NotUsed] = {
    implicit val s: ActorSystem = system
    implicit val m: Materializer = materializer
    Flow[HttpRequest].map(_.asScala).via(delegate).map(_.asJava)
  }

  override def orElse(alternative: Route): Route =
    alternative match {
      case adapt: RouteAdapter ⇒
        RouteAdapter(delegate ~ adapt.delegate)
    }

  override def seal(system: ActorSystem, materializer: Materializer): Route = {
    seal()
  }

  override def seal(): Route = {
    // TODO: scaladsl.server.Route.seal shouldn't require an implicit RoutingSettings when it can retrieve it itself. See https://github.com/akka/akka-http/issues/1898
    RouteAdapter {
      akka.http.scaladsl.server.directives.BasicDirectives.extractSettings { implicit settings ⇒
        scaladsl.server.Route.seal(delegate)
      }
    }
  }

  override def seal(routingSettings: RoutingSettings, parserSettings: ParserSettings, rejectionHandler: RejectionHandler, exceptionHandler: ExceptionHandler, system: ActorSystem, materializer: Materializer): Route = {
    seal(routingSettings, parserSettings, rejectionHandler, exceptionHandler)
  }

  override def seal(routingSettings: RoutingSettings, parserSettings: ParserSettings, rejectionHandler: RejectionHandler, exceptionHandler: ExceptionHandler): Route = {
    RouteAdapter(scaladsl.server.Route.seal(delegate)(
      routingSettings.asScala,
      parserSettings.asScala,
      rejectionHandler.asScala,
      exceptionHandler.asScala))
  }

  override def toString = s"akka.http.javadsl.server.Route($delegate)"

}

object RouteAdapter {
  def apply(delegate: akka.http.scaladsl.server.Route) = new RouteAdapter(delegate)
}
