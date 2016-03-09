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
import akka.stream.Materializer
import akka.stream.scaladsl.Flow

case class ScalaRoute(toScala: akka.http.scaladsl.server.Route) extends Route {
  override def flow(system: ActorSystem, materializer: Materializer) = scalaFlow(system, materializer).asJava

  private def scalaFlow(system: ActorSystem, materializer: Materializer): Flow[HttpRequest, HttpResponse, NotUsed] = {
    implicit val s = system
    implicit val m = materializer
    scaladsl.server.Route.handlerFlow(toScala)
  }

  override def orElse(alternative: Route): Route = alternative match {
    case ScalaRoute(altRoute) ⇒
      ScalaRoute(toScala ~ altRoute)
  }

  override def seal(system: ActorSystem, materializer: Materializer): Route = {
    implicit val s = system
    implicit val m = materializer

    ScalaRoute(scaladsl.server.Route.seal(toScala))
  }
}
