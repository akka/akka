/*
 * Copyright (C) 2009-2014 Typesafe Inc. <http://www.typesafe.com>
 */

package akka.http.impl.server

import scala.concurrent.ExecutionContext
import scala.reflect.ClassTag
import akka.http.javadsl.server.Parameter
import akka.http.scaladsl.server.directives.{ ParameterDirectives, BasicDirectives }
import akka.http.scaladsl.server.Directive1
import akka.http.scaladsl.server.directives.ParameterDirectives.ParamMagnet

/**
 * INTERNAL API
 */
private[http] class ParameterImpl[T: ClassTag](val underlying: ExecutionContext ⇒ ParamMagnet { type Out = Directive1[T] })
  extends StandaloneExtractionImpl[T] with Parameter[T] {

  //def extract(ctx: RequestContext): Future[T] =
  def directive: Directive1[T] =
    BasicDirectives.extractExecutionContext.flatMap { implicit ec ⇒
      ParameterDirectives.parameter(underlying(ec))
    }
}
