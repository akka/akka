/**
 * Copyright (C) 2017 Lightbend Inc. <http://www.lightbend.com>
 */
package akka.actor.typed
package scaladsl

import akka.typed.testkit.{ EffectfulActorContext, TestKitSettings }
import akka.typed.testkit.scaladsl.TestProbe
import org.junit.runner.RunWith
import org.scalatest.junit.JUnitRunner
import scala.concurrent.duration.DurationInt

@RunWith(classOf[JUnitRunner])
final class ImmutablePartialSpec extends TypedSpec {

  final object `An Actor.immutablePartial behavior (adapted)`
    extends Tests
    with AdaptedSystem

  trait Tests extends StartSupport {

    private implicit val testSettings = TestKitSettings(system)

    override implicit def system: ActorSystem[TypedSpec.Command]

    def `must correctly install the message handler`(): Unit = {
      val probe = TestProbe[Command]("probe")
      val behavior =
        Actor.immutablePartial[Command] {
          case (_, Command2) ⇒
            probe.ref ! Command2
            Actor.same
        }
      val context = new EffectfulActorContext("ctx", behavior, 42, null)

      context.run(Command1)
      context.currentBehavior shouldBe behavior
      probe.expectNoMsg(100.milliseconds)

      context.run(Command2)
      context.currentBehavior shouldBe behavior
      probe.expectMsg(Command2)
    }
  }

  private sealed trait Command
  private case object Command1 extends Command
  private case object Command2 extends Command
}
