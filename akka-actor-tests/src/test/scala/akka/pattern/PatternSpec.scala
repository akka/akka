/**
 * Copyright (C) 2009-2012 Typesafe Inc. <http://www.typesafe.com>
 */

package akka.pattern

import akka.testkit.AkkaSpec
import akka.actor.Props
import akka.actor.Actor
import akka.actor.ActorTimeoutException
import akka.dispatch.Await
import scala.util.Duration
import scala.util.duration._

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

    "complete Future with ActorTimeoutException when actor not terminated within timeout" in {
      val target = system.actorOf(Props[TargetActor])
      target ! Work(250 millis)
      val result = gracefulStop(target, 10 millis)
      intercept[ActorTimeoutException] {
        Await.result(result, 200 millis)
      }
    }
  }
}
