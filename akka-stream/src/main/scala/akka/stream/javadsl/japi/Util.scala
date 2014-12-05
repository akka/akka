/**
 * Copyright (C) 2014 Typesafe Inc. <http://www.typesafe.com>
 */
package akka.stream.javadsl.japi

import scala.collection.immutable

object Util {

  import collection.JavaConverters._
  // FIXME this does not make something an immutable iterable!!
  def immutableIterable[T](iterable: java.lang.Iterable[T]): immutable.Iterable[T] =
    new immutable.Iterable[T] {
      override def iterator: Iterator[T] = iterable.iterator().asScala
    }

}
