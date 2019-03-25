/*
 * Copyright (C) 2009-2019 Lightbend Inc. <https://www.lightbend.com>
 */

package akka.pattern

import akka.actor.Scheduler

import language.postfixOps
import akka.testkit.AkkaSpec

import scala.concurrent.{ Await, ExecutionContextExecutor, Future }
import scala.concurrent.duration._

class RetrySpec extends AkkaSpec with RetrySupport {
  implicit val ec: ExecutionContextExecutor = system.dispatcher
  implicit val scheduler: Scheduler = system.scheduler

  "pattern.retry" must {
    "run a successful Future immediately" in {
      val retried = retry(() => Future.successful(5), 5, 1 second)

      within(3 seconds) {
        Await.result(retried, remaining) should ===(5)
      }
    }

    "run a successful Future only once" in {
      @volatile var counter = 0
      val retried = retry(
        () =>
          Future.successful({
            counter += 1
            counter
          }),
        5,
        1 second)

      within(3 seconds) {
        Await.result(retried, remaining) should ===(1)
      }
    }

    "eventually return a failure for a Future that will never succeed" in {
      val retried = retry(() => Future.failed(new IllegalStateException("Mexico")), 5, 100 milliseconds)

      within(3 second) {
        intercept[IllegalStateException] { Await.result(retried, remaining) }.getMessage should ===("Mexico")
      }
    }

    "return a success for a Future that succeeds eventually" in {
      @volatile var failCount = 0

      def attempt() = {
        if (failCount < 5) {
          failCount += 1
          Future.failed(new IllegalStateException(failCount.toString))
        } else Future.successful(5)
      }

      val retried = retry(() => attempt, 10, 100 milliseconds)

      within(3 seconds) {
        Await.result(retried, remaining) should ===(5)
      }
    }

    "return a failure for a Future that would have succeeded but retires were exhausted" in {
      @volatile var failCount = 0

      def attempt() = {
        if (failCount < 10) {
          failCount += 1
          Future.failed(new IllegalStateException(failCount.toString))
        } else Future.successful(5)
      }

      val retried = retry(() => attempt, 5, 100 milliseconds)

      within(3 seconds) {
        intercept[IllegalStateException] { Await.result(retried, remaining) }.getMessage should ===("6")
      }
    }
  }

}
