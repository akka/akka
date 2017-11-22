/**
 * Copyright (C) 2017 Lightbend Inc. <http://www.lightbend.com>
 */
package akka.typed
package scaladsl

import akka.typed.testkit.TestKitSettings
import akka.typed.testkit.scaladsl.TestProbe
import org.junit.runner.RunWith
import org.scalatest.junit.JUnitRunner

import scala.concurrent.duration._

@RunWith(classOf[JUnitRunner])
final class PartialSpec extends TypedSpec {

  final object `An Actor.partial behavior (native)` extends Tests with NativeSystem

  final object `An Actor.partial behavior (adapted)` extends Tests with AdaptedSystem

  trait Tests extends StartSupport {

    private implicit val testSettings = TestKitSettings(system)

    override implicit def system: ActorSystem[TypedSpec.Command]

    def `must instal partial function as onMessage`(): Unit = {
      val probe = TestProbe[String]("probe")
      val behavior: Behavior[String] =
        Actor.immutable(Actor.partial[String] {
          case (ctx, "hello") ⇒
            probe.ref ! "handled-hello"
            Actor.same
        })

      val it = start[String](behavior)

      it ! "hello"
      probe.expectMsg("handled-hello")

      it ! "nein"
      probe.expectNoMsg(100.millis)
    }
  }
}
