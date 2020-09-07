/*
 * Copyright (C) 2020 Lightbend Inc. <https://www.lightbend.com>
 */

package akka.cluster.typed

import org.scalatest.wordspec.AnyWordSpec

import scala.concurrent.duration._
import akka.actor.typed.internal.BackoffCalculator
import org.scalatest.prop.TableDrivenPropertyChecks
class BackoffCalculatorSpec extends AnyWordSpec with TableDrivenPropertyChecks {

  "BackoffCalculator" should {

    "correctly calculate the delay" in {
      val delayTable =
        Table(
          ("restartCount", "minBackoff", "maxBackoff", "randomFactor", "expectedResult"),
          (0, 0.minutes, 0.minutes, 0d, 0.minutes),
          (0, 5.minutes, 7.minutes, 0d, 5.minutes),
          (2, 5.seconds, 7.seconds, 0d, 7.seconds),
          (2, 5.seconds, 7.days, 0d, 20.seconds),
          (29, 5.minutes, 10.minutes, 0d, 10.minutes),
          (29, 10000.days, 10000.days, 0d, 10000.days),
          (Int.MaxValue, 10000.days, 10000.days, 0d, 10000.days))
      forAll(delayTable) {
        (
            restartCount: Int,
            minBackoff: FiniteDuration,
            maxBackoff: FiniteDuration,
            randomFactor: Double,
            expectedResult: FiniteDuration) =>
          val calculatedValue = BackoffCalculator.calculateDelay(restartCount, minBackoff, maxBackoff, randomFactor)
          assert(calculatedValue === expectedResult)
      }
    }
  }

}
