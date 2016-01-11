/*
 * Copyright (C) 2009-2014 Typesafe Inc. <http://www.typesafe.com>
 */

package akka.http.impl.server

import akka.http.javadsl.server.values.PathMatcher
import akka.japi.Option

import scala.reflect.ClassTag
import akka.http.scaladsl.server.{ PathMatcher ⇒ ScalaPathMatcher }

/**
 * INTERNAL API
 */
private[http] class PathMatcherImpl[T: ClassTag](val matcher: ScalaPathMatcher[Tuple1[T]])
  extends ExtractionImpl[T] with PathMatcher[T] {
  def optional: PathMatcher[Option[T]] = new PathMatcherImpl[Option[T]](matcher.?.map(Option.fromScalaOption))
}