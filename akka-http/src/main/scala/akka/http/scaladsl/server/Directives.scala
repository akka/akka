/*
 * Copyright (C) 2009-2016 Lightbend Inc. <http://www.lightbend.com>
 */

package akka.http.scaladsl.server

import directives._

/**
 * Collects all default directives into one trait for simple importing.
 *
 * See [[akka.http.javadsl.server.AllDirectives]] for JavaDSL equivalent of this trait.
 */
trait Directives extends RouteConcatenation
  with BasicDirectives
  with CacheConditionDirectives
  with CookieDirectives
  with DebuggingDirectives
  with CodingDirectives
  with ExecutionDirectives
  with FileAndResourceDirectives
  with FileUploadDirectives
  with FormFieldDirectives
  with FutureDirectives
  with HeaderDirectives
  with HostDirectives
  with MarshallingDirectives
  with MethodDirectives
  with MiscDirectives
  with ParameterDirectives
  with TimeoutDirectives
  with PathDirectives
  with RangeDirectives
  with RespondWithDirectives
  with RouteDirectives
  with SchemeDirectives
  with SecurityDirectives
  with WebSocketDirectives

object Directives extends Directives
