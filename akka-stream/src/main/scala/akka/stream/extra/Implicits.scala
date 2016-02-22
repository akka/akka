/**
 * Copyright (C) 2014-2016 Lightbend Inc. <http://www.lightbend.com>
 */
package akka.stream.extra

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
  implicit class TimedSourceDsl[I, Mat](val source: Source[I, Mat]) extends AnyVal {

    /**
     * Measures time from receiving the first element and completion events - one for each subscriber of this `Flow`.
     */
    def timed[O, Mat2](measuredOps: Source[I, Mat] ⇒ Source[O, Mat2], onComplete: FiniteDuration ⇒ Unit): Source[O, Mat2] =
      Timed.timed[I, O, Mat, Mat2](source, measuredOps, onComplete)

    /**
     * Measures rolling interval between immediately subsequent `matching(o: O)` elements.
     */
    def timedIntervalBetween(matching: I ⇒ Boolean, onInterval: FiniteDuration ⇒ Unit): Source[I, Mat] =
      Timed.timedIntervalBetween[I, Mat](source, matching, onInterval)
  }

  /**
   * Provides time measurement utilities on Stream elements.
   *
   * See [[Timed]]
   */
  implicit class TimedFlowDsl[I, O, Mat](val flow: Flow[I, O, Mat]) extends AnyVal {

    /**
     * Measures time from receiving the first element and completion events - one for each subscriber of this `Flow`.
     */
    def timed[Out, Mat2](measuredOps: Flow[I, O, Mat] ⇒ Flow[I, Out, Mat2], onComplete: FiniteDuration ⇒ Unit): Flow[I, Out, Mat2] =
      Timed.timed[I, O, Out, Mat, Mat2](flow, measuredOps, onComplete)

    /**
     * Measures rolling interval between immediately subsequent `matching(o: O)` elements.
     */
    def timedIntervalBetween(matching: O ⇒ Boolean, onInterval: FiniteDuration ⇒ Unit): Flow[I, O, Mat] =
      Timed.timedIntervalBetween[I, O, Mat](flow, matching, onInterval)
  }

}
