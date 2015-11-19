/*
 * Copyright (C) 2009-2014 Typesafe Inc. <http://www.typesafe.com>
 */

package akka.http.scaladsl.server

import java.io.File

import akka.http.scaladsl.model.Multipart
import akka.stream.scaladsl.{ Source, Sink }
import akka.util.ByteString
import directives._

import scala.util.Try

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
  with PathDirectives
  with RangeDirectives
  with RespondWithDirectives
  with RouteDirectives
  with SchemeDirectives
  with SecurityDirectives
  with WebsocketDirectives

object Directives extends Directives
