/**
 * Copyright (C) 2009-2012 Typesafe Inc. <http://www.typesafe.com>
 */

package akka.pattern

import language.postfixOps

import akka.testkit.AkkaSpec
import akka.actor.{ Props, Actor }
import scala.concurrent.Await
import scala.concurrent.util.Duration
import scala.concurrent.util.duration._
import akka.dispatch.{ Future, Promise }

object PatternSpec {
  case class Work(duration: Duration)
  class TargetActor extends Actor {
    def receive = {
      case Work(duration) ⇒ Thread.sleep(duration.toMillis)
    }
  }
}

@org.junit.runner.RunWith(classOf[org.scalatest.junit.JUnitRunner])
class PatternSpec extends AkkaSpec {

  import PatternSpec._

  "pattern.gracefulStop" must {

    "provide Future for stopping an actor" in {
      val target = system.actorOf(Props[TargetActor])
      val result = gracefulStop(target, 5 seconds)
      Await.result(result, 6 seconds) must be(true)
    }

    "complete Future when actor already terminated" in {
      val target = system.actorOf(Props[TargetActor])
      Await.ready(gracefulStop(target, 5 seconds), 6 seconds)
      Await.ready(gracefulStop(target, 1 millis), 1 second)
    }

    "complete Future with AskTimeoutException when actor not terminated within timeout" in {
      val target = system.actorOf(Props[TargetActor])
      target ! Work(250 millis)
      intercept[AskTimeoutException] { Await.result(gracefulStop(target, 10 millis), 200 millis) }
    }
  }

  "pattern.after" must {
    "be completed successfully eventually" in {
      val f = after(1 second, using = system.scheduler)(Promise.successful(5))

      val r = Future.firstCompletedOf(Seq(Promise[Int](), f))
      Await.result(r, remaining) must be(5)
    }

    "be completed abnormally eventually" in {
      val f = after(1 second, using = system.scheduler)(Promise.failed(new IllegalStateException("Mexico")))

      val r = Future.firstCompletedOf(Seq(Promise[Int](), f))
      intercept[IllegalStateException] { Await.result(r, remaining) }.getMessage must be("Mexico")
    }
  }
}
