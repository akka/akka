/*
 * Copyright (C) 2009-2014 Typesafe Inc. <http://www.typesafe.com>
 */

package akka.http.server.japi
package impl

import akka.http.server.{ PathMatcher ⇒ ScalaPathMatcher }

import scala.reflect.ClassTag

/**
 * INTERNAL API
 */
private[japi] class PathMatcherImpl[T: ClassTag](val matcher: ScalaPathMatcher[Tuple1[T]])
  extends ExtractionImpl[T] with PathMatcher[T]