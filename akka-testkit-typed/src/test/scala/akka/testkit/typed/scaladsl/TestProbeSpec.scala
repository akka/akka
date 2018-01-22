/**
 * Copyright (C) 2009-2018 Lightbend Inc. <http://www.lightbend.com>
 */
package akka.testkit.typed.scaladsl

import akka.actor.typed.scaladsl.Behaviors
import akka.testkit.typed.TestKit
import org.scalatest.{ BeforeAndAfterAll, Matchers, WordSpecLike }
import scala.concurrent.duration._

class TestProbeSpec extends TestKit with WordSpecLike with Matchers with BeforeAndAfterAll {

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

  }

  override protected def afterAll(): Unit = {
    shutdown()
  }
}
