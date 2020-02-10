/*
 * Copyright (C) 2009-2020 Lightbend Inc. <https://www.lightbend.com>
 */

package akka.actor.typed.scaladsl

import akka.Done
import akka.actor.testkit.typed.scaladsl.ScalaTestWithActorTestKit
import akka.actor.testkit.typed.scaladsl.TestProbe
import akka.actor.testkit.typed.scaladsl.LogCapturing
import akka.actor.typed
import akka.actor.typed.Behavior
import akka.actor.typed.BehaviorInterceptor
import akka.actor.typed.PostStop
import org.scalatest.wordspec.AnyWordSpecLike

class StopSpec extends ScalaTestWithActorTestKit with AnyWordSpecLike with LogCapturing {
  import BehaviorInterceptor._

  "Stopping an actor" should {

    "execute the post stop when stopping after setup" in {
      val probe = TestProbe[Done]()
      spawn(Behaviors.setup[AnyRef] { _ =>
        Behaviors.stopped { () =>
          probe.ref ! Done
        }
      })
      probe.expectMessage(Done)
    }

    "execute the post stop" in {
      val probe = TestProbe[Done]()
      val ref = spawn(Behaviors.receiveMessage[String] {
        case "stop" =>
          Behaviors.stopped { () =>
            probe.ref ! Done
          }
      })
      ref ! "stop"
      probe.expectMessage(Done)
    }

    "signal PostStop and then execute the post stop" in {
      val probe = TestProbe[String]()
      val ref = spawn(
        Behaviors
          .receiveMessage[String] {
            case "stop" =>
              Behaviors.stopped { () =>
                probe.ref ! "callback"
              }
          }
          .receiveSignal {
            case (_, PostStop) =>
              probe.ref ! "signal"
              Behaviors.same
          })
      ref ! "stop"
      probe.expectMessage("signal")
      probe.expectMessage("callback")
    }

    // #25082
    "execute the post stop when wrapped" in {
      val probe = TestProbe[Done]()
      spawn(Behaviors.setup[AnyRef] { _ =>
        Behaviors.intercept(() =>
          new BehaviorInterceptor[AnyRef, AnyRef] {
            override def aroundReceive(
                context: typed.TypedActorContext[AnyRef],
                message: AnyRef,
                target: ReceiveTarget[AnyRef]): Behavior[AnyRef] = {
              target(context, message)
            }
          })(Behaviors.stopped { () =>
          probe.ref ! Done
        })
      })
      probe.expectMessage(Done)
    }

    // #25096
    "execute the post stop early" in {
      val probe = TestProbe[Done]()
      spawn(Behaviors.stopped { () =>
        probe.ref ! Done
      })

      probe.expectMessage(Done)
    }

  }

}
