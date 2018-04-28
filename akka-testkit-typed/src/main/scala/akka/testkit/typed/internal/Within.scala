/*
 * Copyright (C) 2018 Lightbend Inc. <https://www.lightbend.com>
 */

package akka.testkit.typed.internal

import akka.annotation.InternalApi
import akka.testkit.typed.TestKitSettings

import scala.concurrent.duration._
import akka.util.PrettyDuration._
import akka.testkit.typed.scaladsl._

@InternalApi
private[akka] trait Within {

  implicit def settings: TestKitSettings

  private var end: Duration = Duration.Undefined

  /**
   * if last assertion was expectNoMessage, disable timing failure upon within()
   * block end.
   */
  private var finishedProcessing = false

  def setFinished(isFinished: Boolean) = finishedProcessing = isFinished

  def remainingOrDefault(implicit settings: TestKitSettings) = remainingOr(settings.SingleExpectDefaultTimeout.dilated)

  def remaining: FiniteDuration = end match {
    case f: FiniteDuration ⇒ f - now
    case _                 ⇒ throw new AssertionError("`remaining` may not be called outside of `within`")
  }

  def remainingOr(duration: FiniteDuration): FiniteDuration = end match {
    case x if x eq Duration.Undefined ⇒ duration
    case x if !x.isFinite             ⇒ throw new IllegalArgumentException("`end` cannot be infinite")
    case f: FiniteDuration            ⇒ f - now
  }

  def remainingOrDilated(max: Duration)(implicit settings: TestKitSettings): FiniteDuration = max match {
    case x if x eq Duration.Undefined ⇒ remainingOrDefault
    case x if !x.isFinite             ⇒ throw new IllegalArgumentException("max duration cannot be infinite")
    case f: FiniteDuration            ⇒ f.dilated
  }

  protected def within_internal[T](min: FiniteDuration, max: FiniteDuration, f: ⇒ T): T = {
    val _max = max.dilated
    val start = now
    val rem = if (end == Duration.Undefined) Duration.Inf else end - start
    assert(rem >= min, s"required min time $min not possible, only ${rem.pretty} left")

    finishedProcessing = false

    val max_diff = _max min rem
    val prev_end = end
    end = start + max_diff

    val ret = try f finally end = prev_end

    val diff = now - start
    assert(min <= diff, s"block took ${diff.pretty}, should at least have been $min")
    if (!finishedProcessing) {
      assert(diff <= max_diff, s"block took ${diff.pretty}, exceeding ${max_diff.pretty}")
    }

    ret
  }

  /**
   * Obtain current time (`System.nanoTime`) as Duration.
   */
  private def now: FiniteDuration = System.nanoTime.nanos

}
