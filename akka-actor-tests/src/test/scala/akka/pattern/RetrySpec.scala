package akka.pattern

import language.postfixOps

import akka.testkit.{ AkkaSpec }
import akka.actor.{ Props, Actor }
import scala.concurrent.{ Future, Promise, Await }
import scala.concurrent.duration._

class RetrySpec extends AkkaSpec with RetrySupport {
  implicit val ec = system.dispatcher

  "pattern.retry" must {
    "run a successful Future immediatly" in {
      val retried = retry(
        () ⇒ Future.successful(5),
        5,
        1 second,
        system.scheduler
      )

      within(100 milliseconds) {
        Await.result(retried, 100 milliseconds) should ===(5)
      }
    }

    "eventually return a failure for a Future that will never succeed" in {
      val retried = retry(
        () ⇒ Future.failed(new IllegalStateException("Mexico")),
        5,
        100 milliseconds,
        system.scheduler
      )

      within(1 second) {
        intercept[IllegalStateException] { Await.result(retried, 1 second) }.getMessage should ===("Mexico")
      }
    }

    "return a success for a Future that succeeds eventually" in {
      var failCount = 0;

      def attempt() = if (failCount < 5) {
        failCount += 1
        Future.failed(new IllegalStateException(failCount.toString))
      } else Future.successful(5)

      val retried = retry(
        attempt,
        10,
        100 milliseconds,
        system.scheduler
      )

      within(750 milliseconds) {
        Await.result(retried, 1 second) should ===(5)
      }
    }

    "return a failure for a Future that would have succeeded but retires were exhausted" in {
      var failCount = 0;

      def attempt() = if (failCount < 10) {
        failCount += 1
        Future.failed(new IllegalStateException(failCount.toString))
      } else Future.successful(5)

      val retried = retry(
        attempt,
        5,
        100 milliseconds,
        system.scheduler
      )

      within(750 milliseconds) {
        intercept[IllegalStateException] { Await.result(retried, 1 second) }.getMessage should ===("6")
      }
    }
  }

}
