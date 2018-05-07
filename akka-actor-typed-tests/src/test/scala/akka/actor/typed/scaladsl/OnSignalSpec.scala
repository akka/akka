/**
 * Copyright (C) 2017-2018 Lightbend Inc. <https://www.lightbend.com>
 */

package akka.actor.typed
package scaladsl

import akka.Done
import akka.testkit.typed.scaladsl.{ ActorTestKit, TestProbe }

final class OnSignalSpec extends ActorTestKit with TypedAkkaSpecWithShutdown {

  "An Actor.OnSignal behavior" must {
    "must correctly install the signal handler" in {
      val probe = TestProbe[Done]("probe")
      val behavior =
        Behaviors.setup[Nothing] { context ⇒
          val stoppedChild = context.spawn(Behaviors.stopped, "stopped-child")
          context.watch(stoppedChild)
          Behaviors.receiveSignal[Nothing] {
            case (_, Terminated(`stoppedChild`)) ⇒
              probe.ref ! Done
              Behaviors.stopped
          }
        }
      spawn[Nothing](behavior)
      probe.expectMessage(Done)
    }
  }
}
