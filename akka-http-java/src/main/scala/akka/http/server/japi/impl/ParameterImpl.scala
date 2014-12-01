/*
 * Copyright (C) 2009-2014 Typesafe Inc. <http://www.typesafe.com>
 */

package akka.http.server.japi
package impl

import akka.http.server.directives.{ ParameterDirectives, BasicDirectives }
import akka.http.server.{ RequestContext, Directive1 }
import akka.http.server.directives.ParameterDirectives.ParamMagnet

import scala.concurrent.{ Future, ExecutionContext }
import scala.reflect.ClassTag

/**
 * INTERNAL API
 */
private[japi] class ParameterImpl[T: ClassTag](val underlying: ExecutionContext ⇒ ParamMagnet { type Out = Directive1[T] })
  extends StandaloneExtractionImpl[T] with Parameter[T] {

  //def extract(ctx: RequestContext): Future[T] =
  def directive: Directive1[T] =
    BasicDirectives.extractExecutionContext.flatMap { implicit ec ⇒
      ParameterDirectives.parameter(underlying(ec))
    }
}
