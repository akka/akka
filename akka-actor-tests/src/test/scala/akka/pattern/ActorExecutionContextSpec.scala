/*
 * Copyright (C) 2018 Lightbend Inc. <https://www.lightbend.com>
 */

package akka.pattern

import java.util.concurrent.ThreadLocalRandom

import akka.actor.Actor
import akka.actor.NoSerializationVerificationNeeded
import akka.actor.Props
import akka.testkit.AkkaSpec
import akka.testkit.TestProbe

import scala.concurrent.duration._
import scala.concurrent.Future

class ActorExecutionContextSpec extends AkkaSpec {
  "The ActorExecutionContext pattern" must {
    "run thunks inside the actor context" in {
      case class Inc(by: Int) extends NoSerializationVerificationNeeded

      class ValueActor extends Actor with ActorExecutionContext {
        private[this] var _counter: Int = 0

        def receive: Receive = {
          case "get" ⇒ sender() ! _counter
          case Inc(by) ⇒
            akka.pattern.after(ThreadLocalRandom.current().nextInt(10).millis, system.scheduler) {
              Future(_counter += by)
            }
          case "ping" ⇒
            Future {
              sender() ! s"result from $self"
            }
        }
      }

      val probe = TestProbe()
      val actor = system.actorOf(Props(new ValueActor))
      (1 to 100).foreach(i ⇒ probe.send(actor, Inc(i)))

      Thread.sleep(200)

      probe.send(actor, "get")
      // actual concurrency would disrupt the counter but doesn't with ActorExecutionContext
      probe.expectMsg(5050)
    }
  }
}
