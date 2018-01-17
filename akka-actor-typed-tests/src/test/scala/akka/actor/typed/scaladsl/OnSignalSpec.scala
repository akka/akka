/**
 * Copyright (C) 2017-2018 Lightbend Inc. <https://www.lightbend.com>
 */
package akka.actor.typed
package scaladsl

import akka.Done
import akka.testkit.typed.TestKit
import akka.testkit.typed.scaladsl.TestProbe

final class OnSignalSpec extends TestKit with TypedAkkaSpecWithShutdown {

  "An Actor.OnSignal behavior" must {
    "must correctly install the signal handler" in {
      val probe = TestProbe[Done]("probe")
      val behavior =
        ActorBehavior.deferred[Nothing] { context ⇒
          val stoppedChild = context.spawn(ActorBehavior.stopped, "stopped-child")
          context.watch(stoppedChild)
          ActorBehavior.onSignal[Nothing] {
            case (_, Terminated(`stoppedChild`)) ⇒
              probe.ref ! Done
              ActorBehavior.stopped
          }
        }
      spawn[Nothing](behavior)
      probe.expectMsg(Done)
    }
  }
}
