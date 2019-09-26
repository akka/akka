/*
 * Copyright (C) 2009-2019 Lightbend Inc. <https://www.lightbend.com>
 */

package akka.pattern

import akka.actor.Scheduler

import language.postfixOps
import scala.collection.mutable.ListBuffer

import akka.testkit.AkkaSpec
import org.scalatest.concurrent.Eventually
import org.scalatest.time.Span
import scala.concurrent.{ Await, ExecutionContextExecutor, Future }
import scala.concurrent.duration._

class RetrySpec extends AkkaSpec with RetrySupport with Eventually {

  override implicit val patience: PatienceConfig = PatienceConfig(1 second, Span(10, org.scalatest.time.Millis))

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

    "return the result after retrying with a backoff" in {
      @volatile var failCount = 0

      def attempt() = {
        failCount += 1
        Future.failed(new IllegalStateException(failCount.toString))
      }

      retry(attempt, 3, 100 milliseconds, 2)

      within(100 milliseconds, 3 second) {
        eventually {
          failCount should ===(2)
        }
      }

      within(200 milliseconds, 3 second) {
        eventually {
          failCount should ===(3)
        }
      }

      within(400 milliseconds, 3 second) {
        eventually {
          failCount should ===(4)
        }
      }
    }

    "execute the provided function during every retry" in {
      val s = new ListBuffer[String]()
      val attempts = 3
      val delay = 100 milliseconds
      val backoff = 2

      retryF(() ⇒ Future.failed(new Exception("oups")), attempts, delay, backoff) {
        case (t, currentRetry) ⇒
          s.append(s"${t.getMessage}, will retry in ${delay * math.pow(backoff, attempts - currentRetry + 1)}")
      }

      eventually {
        s.mkString(System.lineSeparator) should ===("""|oups, will retry in 200 milliseconds
             |oups, will retry in 400 milliseconds
             |oups, will retry in 800 milliseconds""".stripMargin)
      }
    }
  }

}
