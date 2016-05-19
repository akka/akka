/*
 * Copyright (C) 2009-2016 Lightbend Inc. <http://www.lightbend.com>
 */

package akka.http.javadsl.server

import akka.http.scaladsl.server
import akka.japi.pf.PFBuilder
import akka.http.javadsl.settings.RoutingSettings
import akka.http.impl.util.JavaMapping.Implicits._
import RoutingJavaMapping._

object ExceptionHandler {
  /**
   * Creates a new builder DSL for creating an ExceptionHandler
   */
  def newBuilder: ExceptionHandlerBuilder = new ExceptionHandlerBuilder()

  /** INTERNAL API */
  def of(pf: PartialFunction[Throwable, Route]) = new ExceptionHandler(server.ExceptionHandler(pf.andThen(_.delegate)))
}

/**
 * Handles exceptions by turning them into routes. You can create an exception handler in Java code like the following example:
 * <pre>
 *     ExceptionHandler myHandler = ExceptionHandler.of (ExceptionHandler.newPFBuilder()
 *         .match(IllegalArgumentException.class, x -> Directives.complete(StatusCodes.BAD_REQUEST))
 *         .build()
 *     ));
 * </pre>
 */
final class ExceptionHandler private (val asScala: server.ExceptionHandler) {
  /**
   * Creates a new [[ExceptionHandler]] which uses the given one as fallback for this one.
   */
  def withFallback(that: ExceptionHandler): ExceptionHandler = new ExceptionHandler(asScala.withFallback(that.asScala))

  /**
   * "Seals" this handler by attaching a default handler as fallback if necessary.
   */
  def seal(settings: RoutingSettings): ExceptionHandler = new ExceptionHandler(asScala.seal(settings.asScala))
}
