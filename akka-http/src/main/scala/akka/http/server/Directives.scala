/*
 * Copyright (C) 2009-2014 Typesafe Inc. <http://www.typesafe.com>
 */

package akka.http.server

import directives._

// FIXME: the comments are kept as a reminder which directives are not yet imported

trait Directives extends RouteConcatenation
  with AuthenticationDirectives
  with BasicDirectives
  with CacheConditionDirectives
  //with ChunkingDirectives
  with CookieDirectives
  with DebuggingDirectives
  with CodingDirectives
  with ExecutionDirectives
  with FileAndResourceDirectives
  with FormFieldDirectives
  with FutureDirectives
  with HeaderDirectives
  with HostDirectives
  with MarshallingDirectives
  with MethodDirectives
  with MiscDirectives
  with ParameterDirectives
  with PathDirectives
  with RangeDirectives
  with RespondWithDirectives
  with RouteDirectives
  with SchemeDirectives
  with SecurityDirectives
  with WebsocketDirectives

object Directives extends Directives
