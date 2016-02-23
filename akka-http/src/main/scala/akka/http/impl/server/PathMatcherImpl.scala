/*
 * Copyright (C) 2009-2016 Lightbend Inc. <http://www.lightbend.com>
 */

package akka.http.impl.server

import java.util.Optional

import akka.http.javadsl.server.values.PathMatcher

import scala.reflect.ClassTag
import akka.http.scaladsl.server.{ PathMatcher ⇒ ScalaPathMatcher }

import scala.compat.java8.OptionConverters

/**
 * INTERNAL API
 */
private[http] class PathMatcherImpl[T: ClassTag](val matcher: ScalaPathMatcher[Tuple1[T]])
  extends ExtractionImpl[T] with PathMatcher[T] {
  def optional: PathMatcher[Optional[T]] = new PathMatcherImpl[Optional[T]](matcher.?.map(OptionConverters.toJava))
}