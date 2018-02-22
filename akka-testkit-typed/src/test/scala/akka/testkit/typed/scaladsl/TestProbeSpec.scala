/**
 * Copyright (C) 2009-2018 Lightbend Inc. <http://www.lightbend.com>
 */
package akka.testkit.typed.scaladsl

import akka.actor.typed.scaladsl.Behaviors
import akka.testkit.typed.TestKit
import org.scalatest.{ BeforeAndAfterAll, Matchers, WordSpecLike }
import scala.concurrent.duration._

class TestProbeSpec extends TestKit with WordSpecLike with Matchers with BeforeAndAfterAll {

  def compileOnlyApiTest(): Unit = {
    val probe = TestProbe[AnyRef]()
    probe.fishForMessage(100.millis) {
      case _ ⇒ FishingOutcomes.complete
    }
    probe.awaitAssert({
      "result"
    })
    probe.expectMessageType[String]
    probe.expectMessage("whoa")
    probe.expectNoMessage()
    probe.expectNoMessage(300.millis)
    probe.expectTerminated(system.deadLetters, 100.millis)
    probe.within(100.millis) {
      "result"
    }
  }

  "The test probe" must {

    "allow probing for actor stop when actor already stopped" in {
      case object Stop
      val probe = TestProbe()
      val ref = spawn(Behaviors.stopped)
      probe.expectTerminated(ref, 100.millis)
    }

    "allow probing for actor stop when actor has not stopped yet" in {
      case object Stop
      val probe = TestProbe()
      val ref = spawn(Behaviors.immutable[Stop.type]((ctx, message) ⇒
        Behaviors.withTimers { (timer) ⇒
          timer.startSingleTimer("key", Stop, 300.millis)

          Behaviors.immutable((ctx, stop) ⇒
            Behaviors.stopped
          )
        }
      ))
      ref ! Stop
      // race, but not sure how to test in any other way
      probe.expectTerminated(ref, 500.millis)
    }

    "allow fishing for message" in {

      val probe = TestProbe[String]()

      probe.ref ! "one"
      probe.ref ! "two"

      val result = probe.fishForMessage(300.millis) {
        case "one" ⇒ FishingOutcomes.continue
        case "two" ⇒ FishingOutcomes.complete
      }

      result should ===(List("one", "two"))
    }

    "allow failing when fishing for message" in {

      val probe = TestProbe[String]()

      probe.ref ! "one"
      probe.ref ! "two"

      intercept[AssertionError] {
        probe.fishForMessage(300.millis) {
          case "one" ⇒ FishingOutcomes.continue
          case "two" ⇒ FishingOutcomes.fail("not the fish I'm looking for")
        }
      }
    }

    "fail for unknown message when fishing for messages" in {
      val probe = TestProbe[String]()

      probe.ref ! "one"
      probe.ref ! "two"

      intercept[AssertionError] {
        probe.fishForMessage(300.millis) {
          case "one" ⇒ FishingOutcomes.continue
        }
      }
    }

    "time out when fishing for messages" in {
      val probe = TestProbe[String]()

      probe.ref ! "one"

      intercept[AssertionError] {
        probe.fishForMessage(300.millis) {
          case "one" ⇒ FishingOutcomes.continue
        }
      }
    }

  }

  override protected def afterAll(): Unit = {
    shutdown()
  }
}
