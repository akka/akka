/*
 * Copyright (C) 2009-2020 Lightbend Inc. <https://www.lightbend.com>
 */

package akka.pattern

import language.postfixOps

import akka.testkit.{ AkkaSpec, TestLatch }
import akka.actor.{ Actor, Props }
import scala.concurrent.{ Await, Future, Promise }
import scala.concurrent.duration._

object PatternSpec {
  final case class Work(duration: Duration)
  class TargetActor extends Actor {
    def receive = {
      case (testLatch: TestLatch, duration: FiniteDuration) =>
        Await.ready(testLatch, duration)
    }
  }
}

class PatternSpec extends AkkaSpec {
  implicit val ec = system.dispatcher
  import PatternSpec._

  "pattern.gracefulStop" must {

    "provide Future for stopping an actor" in {
      val target = system.actorOf(Props[TargetActor])
      val result = gracefulStop(target, 5 seconds)
      Await.result(result, 6 seconds) should ===(true)
    }

    "complete Future when actor already terminated" in {
      val target = system.actorOf(Props[TargetActor])
      Await.ready(gracefulStop(target, 5 seconds), 6 seconds)
      Await.ready(gracefulStop(target, 1 millis), 1 second)
    }

    "complete Future with AskTimeoutException when actor not terminated within timeout" in {
      val target = system.actorOf(Props[TargetActor])
      val latch = TestLatch()
      target ! ((latch, remainingOrDefault))
      intercept[AskTimeoutException] { Await.result(gracefulStop(target, 500 millis), remainingOrDefault) }
      latch.open()
    }
  }

  "pattern.after" must {
    "be completed successfully eventually" in {
      // TODO after is unfortunately shadowed by ScalaTest, fix as part of #3759
      val f = akka.pattern.after(1 second, using = system.scheduler)(Future.successful(5))

      val r = Future.firstCompletedOf(Seq(Promise[Int]().future, f))
      Await.result(r, remainingOrDefault) should ===(5)
    }

    "be completed abnormally eventually" in {
      // TODO after is unfortunately shadowed by ScalaTest, fix as part of #3759
      val f = akka.pattern.after(1 second, using = system.scheduler)(Future.failed(new IllegalStateException("Mexico")))

      val r = Future.firstCompletedOf(Seq(Promise[Int]().future, f))
      intercept[IllegalStateException] { Await.result(r, remainingOrDefault) }.getMessage should ===("Mexico")
    }
  }
}
