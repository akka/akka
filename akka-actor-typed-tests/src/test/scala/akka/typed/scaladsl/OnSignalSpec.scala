/**
 * Copyright (C) 2017 Lightbend Inc. <http://www.lightbend.com>
 */
package akka.typed
package scaladsl

import akka.Done
import akka.typed.testkit.TestKitSettings
import akka.typed.testkit.scaladsl.TestProbe
import org.junit.runner.RunWith
import org.scalatest.junit.JUnitRunner

@RunWith(classOf[JUnitRunner])
final class OnSignalSpec extends TypedSpec {

  final object `An Actor.onSignal behavior (native)` extends Tests with NativeSystem

  final object `An Actor.onSignal behavior (adapted)` extends Tests with AdaptedSystem

  trait Tests extends StartSupport {

    private implicit val testSettings = TestKitSettings(system)

    override implicit def system: ActorSystem[TypedSpec.Command]

    def `must correctly install the signal handler`(): Unit = {
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
