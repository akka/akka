/*
 * Copyright (C) 2017-2020 Lightbend Inc. <https://www.lightbend.com>
 */

package akka.actor.typed
package scaladsl

import akka.Done
import akka.actor.testkit.typed.scaladsl.ScalaTestWithActorTestKit
import akka.actor.testkit.typed.scaladsl.TestProbe
import akka.actor.testkit.typed.scaladsl.LogCapturing
import org.scalatest.wordspec.AnyWordSpecLike

final class OnSignalSpec extends ScalaTestWithActorTestKit with AnyWordSpecLike with LogCapturing {

  "An Actor.OnSignal behavior" must {
    "must correctly install the signal handler" in {
      val probe = TestProbe[Done]("probe")
      val behavior =
        Behaviors.setup[Nothing] { context =>
          val stoppedChild = context.spawn(Behaviors.stopped, "stopped-child")
          context.watch(stoppedChild)
          Behaviors.receiveSignal[Nothing] {
            case (_, Terminated(`stoppedChild`)) =>
              probe.ref ! Done
              Behaviors.stopped
          }
        }
      spawn[Nothing](behavior)
      probe.expectMessage(Done)
    }

    def stopper(probe: TestProbe[Done]) =
      Behaviors
        .receiveMessage[String] {
          case "stop" =>
            Behaviors.stopped
        }
        .receiveSignal {
          case (_, PostStop) =>
            probe.ref ! Done
            Behaviors.same
        }

    "execute post stop for child" in {
      val probe = createTestProbe[Done]("post-stop-child")
      val stopperRef = spawn(stopper(probe))
      stopperRef ! "stop"
      probe.expectMessage(Done)

    }

    "execute post stop for guardian behavior" in {
      val probe = createTestProbe[Done]("post-stop-probe")
      val system = ActorSystem(stopper(probe), "work")
      try {
        system ! "stop"
        probe.expectMessage(Done)
      } finally {
        system.terminate()
      }
    }
  }
}
