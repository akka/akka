/**
 * Copyright (C) 2017-2018 Lightbend Inc. <https://www.lightbend.com>
 */

package akka.actor.typed
package scaladsl

import akka.Done
import akka.NotUsed
import akka.testkit.typed.scaladsl.{ ActorTestKit, TestProbe }

final class GracefulStopSpec extends ActorTestKit with TypedAkkaSpecWithShutdown {

  "Graceful stop" must {

    "properly stop the children and perform the cleanup" in {
      val probe = TestProbe[String]("probe")

      val behavior =
        Behaviors.setup[akka.NotUsed] { context ⇒
          val c1 = context.spawn[NotUsed](Behaviors.receiveSignal {
            case (_, PostStop) ⇒
              probe.ref ! "child-done"
              Behaviors.stopped
          }, "child1")

          val c2 = context.spawn[NotUsed](Behaviors.receiveSignal {
            case (_, PostStop) ⇒
              probe.ref ! "child-done"
              Behaviors.stopped
          }, "child2")

          Behaviors.stopped {
            Behaviors.receiveSignal {
              case (ctx, PostStop) ⇒
                // cleanup function body
                probe.ref ! "parent-done"
                Behaviors.same
            }
          }
        }

      spawn(behavior)
      probe.expectMessage("child-done")
      probe.expectMessage("child-done")
      probe.expectMessage("parent-done")
    }

    "properly perform the cleanup and stop itself for no children case" in {
      val probe = TestProbe[Done]("probe")

      val behavior =
        Behaviors.setup[akka.NotUsed] { context ⇒
          // do not spawn any children
          Behaviors.stopped {
            Behaviors.receiveSignal {
              case (ctx, PostStop) ⇒
                // cleanup function body
                probe.ref ! Done
                Behaviors.same
            }
          }
        }

      spawn(behavior)
      probe.expectMessage(Done)
    }
  }

}
