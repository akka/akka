/*
 * Copyright (C) 2017-2018 Lightbend Inc. <https://www.lightbend.com>
 */

package akka.actor.typed
package scaladsl

import akka.Done
import akka.actor.testkit.typed.scaladsl.ScalaTestWithActorTestKit
import akka.actor.testkit.typed.scaladsl.TestProbe
import org.scalatest.WordSpecLike

final class OnSignalSpec extends ScalaTestWithActorTestKit with WordSpecLike {

  "An Actor.OnSignal behavior" must {
    "must correctly install the signal handler" in {
      val probe = TestProbe[Done]("probe")
      val behavior =
        Behaviors.setup[Nothing] { ctx ⇒
          val stoppedChild = ctx.spawn(Behaviors.stopped, "stopped-child")
          ctx.watch(stoppedChild)
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
