/**
 * Copyright (C) 2014 Typesafe Inc. <http://www.typesafe.com>
 */
package akka.stream.extra

import scala.language.implicitConversions
import akka.stream.scaladsl.Duct
import akka.stream.scaladsl.Flow
import scala.concurrent.duration.FiniteDuration

/**
 * Additional [[akka.stream.scaladsl.Flow]] and [[akka.stream.scaladsl.Duct]] operators.
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

  /**
   * Provides time measurement utilities on Stream elements.
   *
   * See [[Timed]]
   */
  implicit class TimedFlowDsl[I](val flow: Flow[I]) extends AnyVal {

    /**
     * Measures time from receieving the first element and completion events - one for each subscriber of this `Flow`.
     */
    def timed[O](measuredOps: Flow[I] ⇒ Flow[O], onComplete: FiniteDuration ⇒ Unit): Flow[O] =
      Timed.timed[I, O](flow, measuredOps, onComplete)

    /**
     * Measures rolling interval between immediatly subsequent `matching(o: O)` elements.
     */
    def timedIntervalBetween(matching: I ⇒ Boolean, onInterval: FiniteDuration ⇒ Unit): Flow[I] =
      Timed.timedIntervalBetween[I](flow, matching, onInterval)
  }

}