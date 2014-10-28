/**
 * Copyright (C) 2014 Typesafe Inc. <http://www.typesafe.com>
 */
package akka.stream.extra

import scala.language.implicitConversions
import akka.stream.scaladsl.Flow
import akka.stream.scaladsl.Source
import scala.concurrent.duration.FiniteDuration

/**
 * Additional [[akka.stream.scaladsl.Flow]] and [[akka.stream.scaladsl.Flow]] operators.
 */
object Implicits {

  /**
   * Provides time measurement utilities on Stream elements.
   *
   * See [[Timed]]
   */
  implicit class TimedSourceDsl[I](val flow: Source[I]) extends AnyVal {

    /**
     * Measures time from receieving the first element and completion events - one for each subscriber of this `Flow`.
     */
    def timed[O](measuredOps: Source[I] ⇒ Source[O], onComplete: FiniteDuration ⇒ Unit): Source[O] =
      Timed.timed[I, O](flow, measuredOps, onComplete)

    /**
     * Measures rolling interval between immediatly subsequent `matching(o: O)` elements.
     */
    def timedIntervalBetween(matching: I ⇒ Boolean, onInterval: FiniteDuration ⇒ Unit): Source[I] =
      Timed.timedIntervalBetween[I](flow, matching, onInterval)
  }

  /**
   * Provides time measurement utilities on Stream elements.
   *
   * See [[Timed]]
   */
  implicit class TimedFlowDsl[I, O](val flow: Flow[I, O]) extends AnyVal {

    /**
     * Measures time from receieving the first element and completion events - one for each subscriber of this `Flow`.
     */
    def timed[Out](measuredOps: Flow[I, O] ⇒ Flow[O, Out], onComplete: FiniteDuration ⇒ Unit): Flow[O, Out] =
      Timed.timed[I, O, Out](flow, measuredOps, onComplete)

    /**
     * Measures rolling interval between immediatly subsequent `matching(o: O)` elements.
     */
    def timedIntervalBetween(matching: O ⇒ Boolean, onInterval: FiniteDuration ⇒ Unit): Flow[I, O] =
      Timed.timedIntervalBetween[I, O](flow, matching, onInterval)
  }

}