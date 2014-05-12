/**
 * Copyright (C) 2009-2014 Typesafe Inc. <http://www.typesafe.com>
 */
package akka.stream.extra

import scala.language.implicitConversions
import akka.stream.scaladsl.Duct
import akka.stream.scaladsl.Flow
import scala.concurrent.duration.Duration

/** Provides additional operators on streams */
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
  

  /**
   * Provides time measurement utilities on Stream elements.
   *
   * See [[Timed]]
   */
  implicit class TimedFlowDsl[T](val flow: Flow[T]) extends AnyVal {

    /**
     * Measures time from inputing the first element and emiting the last element by the contained flow.
     */
    def timed(measuredOps: Flow[T] ⇒ Flow[T], onComplete: Duration ⇒ Unit): Flow[T] =
      Timed.timed(flow, measuredOps, onComplete)

    /**
     * Measures rolling interval between `matching(o: O)` elements.
     */
    def timedIntervalBetween(matching: T ⇒ Boolean, onInterval: Duration ⇒ Unit): Flow[T] =
      Timed.timedIntervalBetween(flow, matching, onInterval)
  }

}