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
final class GracefulStopSpec extends TypedSpec {

  final object `An Actor.onSignal behavior (native)` extends Tests with NativeSystem

  final object `An Actor.onSignal behavior (adapted)` extends Tests with AdaptedSystem

  trait Tests extends StartSupport {

    private implicit val testSettings = TestKitSettings(system)

    override implicit def system: ActorSystem[TypedSpec.Command]

    def `must properly stop the children and perform the cleanup when doing graceful stop`(): Unit = {
      val probe = TestProbe[Done]("probe")
      val probeCld1 = TestProbe[Done]("probeCld1")
      val probeCld2 = TestProbe[Done]("probeCld2")

      val behavior =
        Actor.deferred[akka.NotUsed] { context ⇒
          val c1 = context.spawn(Actor.immutable[akka.NotUsed] {
            (_, _) ⇒ Actor.unhandled
          } onSignal {
            case (_, PostStop) ⇒
              probeCld1.ref ! Done
              Actor.stopped
          }, "child1")

          val c2 = context.spawn(Actor.immutable[akka.NotUsed] {
            (_, _) ⇒ Actor.unhandled
          } onSignal {
            case (_, PostStop) ⇒
              probeCld2.ref ! Done
              Actor.stopped
          }, "child2")

          Actor.stopped {
            Actor.onSignal {
              case (ctx, PostStop) ⇒
                // cleanup function body
                probe.ref ! Done
                Actor.same
            }
          }
        }
      start[akka.NotUsed](behavior)
      probeCld1.expectMsg(Done)
      probeCld2.expectMsg(Done)

      // if this ever fails with Done not received, would be good to look into possible race in ActorCell
      probe.expectMsg(Done)
    }

    def `must properly perform the cleanup and stop itself for no children case`(): Unit = {
      val probe = TestProbe[Done]("probe")

      val behavior =
        Actor.deferred[akka.NotUsed] { context ⇒
          // do not spawn any children
          Actor.stopped {
            Actor.onSignal {
              case (ctx, PostStop) ⇒
                // cleanup function body
                probe.ref ! Done
                Actor.same
            }
          }
        }
      start[akka.NotUsed](behavior)

      // if this ever fails with Done not received, would be good to look into possible race in ActorCell
      probe.expectMsg(Done)
    }
  }
}
