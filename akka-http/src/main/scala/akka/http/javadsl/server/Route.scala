/*
 * Copyright (C) 2009-2016 Lightbend Inc. <http://www.lightbend.com>
 */

package akka.http.javadsl.server

import akka.stream.javadsl.Flow
import akka.http.javadsl.model.HttpRequest
import akka.http.javadsl.model.HttpResponse
import akka.http.scaladsl
import akka.actor.ActorSystem
import akka.stream.Materializer
import akka.http.scaladsl.server.Directives._
import scala.annotation.varargs
import akka.NotUsed

/**
 * In the Java DSL, a Route can only consist of combinations of the built-in directives. A Route can not be
 * instantiated directly.
 *
 * However, the built-in directives may be combined methods like:
 *
 * <pre>
 * Route myDirective(String test, Supplier<Route> inner) {
 *     return path("fixed", () ->
 *         path(test),
 *             inner
 *         )
 *     );
 * }
 *
 * </pre>
 *
 * The above example will invoke [inner] whenever the path "fixed/{test}" is matched, where "{test}"
 * is the actual String that was given as method argument.
 */
trait Route {
  /** INTERNAL API */
  private[http] def toScala: scaladsl.server.Route

  def flow(system: ActorSystem, materializer: Materializer): Flow[HttpRequest, HttpResponse, NotUsed]
  def seal(system: ActorSystem, materializer: Materializer): Route
  def orElse(alternative: Route): Route
}
