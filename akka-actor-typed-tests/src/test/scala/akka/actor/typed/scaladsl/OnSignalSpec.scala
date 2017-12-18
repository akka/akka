/**
 * Copyright (C) 2017 Lightbend Inc. <http://www.lightbend.com>
 */
package akka.actor.typed
package scaladsl

import akka.Done
import akka.typed.testkit.TestKitSettings
import akka.typed.testkit.scaladsl.TestProbe

final class OnSignalSpec extends TypedSpec with StartSupport {

  private implicit val testSettings = TestKitSettings(system)

  "An Actor.OnSignal behavior" must {
    "must correctly install the signal handler" in {
      val probe = TestProbe[Done]("probe")
      val behavior =
        Actor.deferred[Nothing] { context ⇒
          val stoppedChild = context.spawn(Actor.stopped, "stopped-child")
          context.watch(stoppedChild)
          Actor.onSignal[Nothing] {
            case (_, Terminated(`stoppedChild`)) ⇒
              probe.ref ! Done
              Actor.stopped
          }
        }
      start[Nothing](behavior)
      probe.expectMsg(Done)
    }
  }
}
