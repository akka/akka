/*
 * Copyright (C) 2009-2016 Lightbend Inc. <http://www.lightbend.com>
 */

package akka.http.javadsl.server

import scala.reflect.ClassTag

/**
 * A marker trait for an unmarshaller that converts an HttpRequest to a value of type T.
 */
trait Unmarshaller[T] {
  def classTag: ClassTag[T]
}
