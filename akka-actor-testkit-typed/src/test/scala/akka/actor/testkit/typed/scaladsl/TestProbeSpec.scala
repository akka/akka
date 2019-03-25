/*
 * Copyright (C) 2009-2019 Lightbend Inc. <https://www.lightbend.com>
 */

package akka.actor.testkit.typed.scaladsl

import akka.actor.typed.scaladsl.Behaviors
import com.typesafe.config.ConfigFactory

import scala.concurrent.duration._
import org.scalatest.WordSpecLike

class TestProbeSpec extends ScalaTestWithActorTestKit with WordSpecLike {

  import TestProbeSpec._

  def compileOnlyApiTest(): Unit = {
    val probe = TestProbe[AnyRef]()
    probe.fishForMessage(100.millis) {
      case _ => FishingOutcomes.complete
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
      val probe = TestProbe()
      val ref = spawn(Behaviors.stopped)
      probe.expectTerminated(ref, 100.millis)
    }

    "allow probing for actor stop when actor has not stopped yet" in {
      case object Stop
      val probe = TestProbe()
      val ref = spawn(Behaviors.receive[Stop.type]((context, message) =>
        Behaviors.withTimers { (timer) =>
          timer.startSingleTimer("key", Stop, 300.millis)

          Behaviors.receive((context, stop) => Behaviors.stopped)
        }))
      ref ! Stop
      // race, but not sure how to test in any other way
      probe.expectTerminated(ref, 500.millis)
    }

    "allow fishing for message" in {

      val probe = TestProbe[String]()

      probe.ref ! "one"
      probe.ref ! "two"

      val result = probe.fishForMessage(300.millis) {
        case "one" => FishingOutcomes.continue
        case "two" => FishingOutcomes.complete
      }

      result should ===(List("one", "two"))
    }

    "allow failing when fishing for message" in {

      val probe = TestProbe[String]()

      probe.ref ! "one"
      probe.ref ! "two"

      intercept[AssertionError] {
        probe.fishForMessage(300.millis) {
          case "one" => FishingOutcomes.continue
          case "two" => FishingOutcomes.fail("not the fish I'm looking for")
        }
      }
    }

    "throw an AssertionError when the fishing probe times out" in {
      val probe = TestProbe[AnyRef]()

      assertThrows[AssertionError] {
        probe.fishForMessage(100.millis) { _ =>
          Thread.sleep(150)
          FishingOutcomes.complete
        }
      }
    }

    "fail for unknown message when fishing for messages" in {
      val probe = TestProbe[String]()

      probe.ref ! "one"
      probe.ref ! "two"

      intercept[AssertionError] {
        probe.fishForMessage(300.millis) {
          case "one" => FishingOutcomes.continue
        }
      }
    }

    "time out when fishing for messages" in {
      val probe = TestProbe[String]()

      probe.ref ! "one"

      intercept[AssertionError] {
        probe.fishForMessage(300.millis) {
          case "one" => FishingOutcomes.continue
        }
      }
    }

    "allow receiving several messages" in {
      val probe = TestProbe[String]()

      probe.ref ! "one"
      probe.ref ! "two"
      probe.ref ! "three"

      val result = probe.receiveMessages(3)

      result should ===(List("one", "two", "three"))
    }

    "time out when not receiving several messages" in {
      val probe = TestProbe[String]()

      probe.ref ! "one"

      intercept[AssertionError] {
        probe.receiveMessages(3, 50.millis)
      }
    }

    "allow receiving one message of type TestProbe[M]" in {
      val probe = createTestProbe[EventT]()
      eventsT(10).forall { e =>
        probe.ref ! e
        probe.receiveMessage == e
      } should ===(true)

      probe.expectNoMessage()
    }

    "timeout if expected single message is not received by a provided timeout" in {
      intercept[AssertionError](createTestProbe[EventT]().receiveMessage(100.millis))
    }

    "support watch and stop of probe" in {
      val probe1 = TestProbe[String]()
      val probe2 = TestProbe[String]()
      probe1.stop()
      probe2.expectTerminated(probe1.ref, probe2.remainingOrDefault)
    }
  }
}

object TestProbeSpec {

  val timeoutConfig = ConfigFactory.parseString("""
      akka.actor.testkit.typed.default-timeout = 100ms
      akka.test.default-timeout = 100ms""")

  /** Helper events for tests. */
  final case class EventT(id: Long)

  /** Creates the `expected` number of events to test. */
  def eventsT(expected: Int): Seq[EventT] =
    for (n <- 1 to expected) yield EventT(n)
}

class TestProbeTimeoutSpec extends ScalaTestWithActorTestKit(TestProbeSpec.timeoutConfig) with WordSpecLike {

  import TestProbeSpec._

  "The test probe" must {

    "timeout if expected single message is not received by the default timeout" in {
      intercept[AssertionError](createTestProbe[EventT]().receiveMessage())
    }
  }
}
