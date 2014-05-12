/**
 * Copyright (C) 2014 Typesafe Inc. <http://www.typesafe.com>
 */
package akka.stream.extra

import akka.stream.scaladsl.Duct
import akka.stream.scaladsl.Flow

/**
 * Additional [[akka.stream.scaladsl.Flow]] and [[akka.stream.scaladsl.Duct]]
 * operators.
 */
object Implicits {

  /**
   * Implicit enrichment for stream logging.
   *
   * @see [[Log]]
   */
  implicit class LogFlowDsl[T](val flow: Flow[T]) extends AnyVal {
    def log(): Flow[T] = flow.transform(Log())
  }

  /**
   * Implicit enrichment for stream logging.
   *
   * @see [[Log]]
   */
  implicit class LogDuctDsl[In, Out](val duct: Duct[In, Out]) extends AnyVal {
    def log(): Duct[In, Out] = duct.transform(Log())
  }

}