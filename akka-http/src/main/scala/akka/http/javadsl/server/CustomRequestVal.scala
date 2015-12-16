/*
 * Copyright (C) 2009-2015 Typesafe Inc. <http://www.typesafe.com>
 */

package akka.http.javadsl.server

import akka.http.impl.server.{ RequestContextImpl, StandaloneExtractionImpl }
import akka.http.scaladsl.server.Directive1
import akka.http.scaladsl.server.directives.BasicDirectives._

import scala.reflect.ClassTag

/**
 * Extend from this class and implement `extractValue` to create a custom request val.
 */
abstract class CustomRequestVal[T](clazz: Class[T]) extends StandaloneExtractionImpl[T]()(ClassTag(clazz)) {
  final def directive: Directive1[T] = extract(ctx ⇒ extractValue(RequestContextImpl(ctx)))

  protected def extractValue(ctx: RequestContext): T
}
