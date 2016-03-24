/*
 * Copyright (C) 2009-2016 Lightbend Inc. <http://www.lightbend.com>
 */
package akka.http.javadsl.server.directives

import akka.NotUsed
import akka.actor.ActorSystem
import akka.http.javadsl.model.HttpRequest
import akka.http.javadsl.model.HttpResponse
import akka.http.javadsl.server.JavaScalaTypeEquivalence._
import akka.http.javadsl.server.Route
import akka.http.scaladsl
import akka.http.scaladsl.server.RouteConcatenation._
import akka.stream.{ javadsl, Materializer }
import akka.stream.scaladsl.Flow

/** INTERNAL API */
final class RouteAdapter(val delegate: akka.http.scaladsl.server.Route) extends Route {
  import RouteAdapter._

  override def flow(system: ActorSystem, materializer: Materializer): javadsl.Flow[HttpRequest, HttpResponse, NotUsed] =
    scalaFlow(system, materializer).asJava

  private def scalaFlow(system: ActorSystem, materializer: Materializer): Flow[HttpRequest, HttpResponse, NotUsed] = {
    implicit val s = system
    implicit val m = materializer
    scaladsl.server.Route.handlerFlow(delegate)
  }

  override def orElse(alternative: Route): Route =
    alternative match {
      case adapt: RouteAdapter ⇒
        RouteAdapter(delegate ~ adapt.delegate)
    }

  override def seal(system: ActorSystem, materializer: Materializer): Route = {
    implicit val s = system
    implicit val m = materializer

    RouteAdapter(scaladsl.server.Route.seal(delegate))
  }

  override def toString = s"akka.http.javadsl.server.Route($delegate)"
}

object RouteAdapter {
  def apply(delegate: akka.http.scaladsl.server.Route) = new RouteAdapter(delegate)
}