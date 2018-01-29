/**
 * Copyright (C) 2009-2018 Lightbend Inc. <http://www.lightbend.com>
 */
package akka.actor.typed.internal

import akka.actor.typed.{ ActorRef, PostStop, Signal, Terminated, TypedAkkaSpec }
import akka.actor.typed.scaladsl.Behaviors
import akka.testkit.typed.TestKit
import akka.testkit.typed.scaladsl.TestProbe

import scala.concurrent.duration._

class DelayedStartSpec extends TestKit with TypedAkkaSpec {

  case class GotSignal(signal: Signal)
  def delayedBehavior(probe: ActorRef[AnyRef]) = DelayedStart.delayStart[AnyRef](Behaviors.deferred { ctx ⇒
    probe ! "delayed-init"

    Behaviors.immutable[AnyRef] { (ctx, msg) ⇒

      msg match {
        case "switch" ⇒
          Behaviors.deferred { ctx ⇒
            probe ! "switching"
            Behaviors.immutable { (ctx, msg) ⇒
              probe ! (msg + "-switched")
              Behaviors.same
            }
          }
        case "stop" ⇒
          probe ! "stopping"
          Behaviors.stopped

        case other ⇒
          probe ! other
          Behaviors.same
      }
    }
  })

  "The delayed start behavior" should {

    "delay message passing until told to start delayed behavior" in {

      val probe = TestProbe[AnyRef]()
      val delayed = spawn(delayedBehavior(probe.ref))

      delayed ! "message1"
      delayed ! "switch"
      delayed ! "message2"

      probe.expectNoMessage(200.millis)

      delayed ! DelayedStart.Start
      probe.expectMsg("delayed-init")
      probe.expectMsg("message1")
      probe.expectMsg("switching")
      probe.expectMsg("message2-switched")
    }

    "delay stopping" in {
      val probe = TestProbe[AnyRef]()
      val delayed = spawn(delayedBehavior(probe.ref))
      // FIXME missing a watch in typed testprobe
      val watcher = spawn(Behaviors.deferred[AnyRef] { ctx ⇒
        ctx.watch(delayed)
        probe.ref ! "watching"
        Behaviors.onSignal {
          case (_, s) ⇒
            probe.ref ! GotSignal(s)
            Behaviors.same
        }
      })

      probe.expectMsg("watching")
      delayed ! "message1"
      delayed ! "stop"
      probe.expectNoMessage(200.millis)

      delayed ! DelayedStart.Start
      probe.expectMsg("delayed-init")
      probe.expectMsg("message1")
      probe.expectMsg("stopping")
      probe.expectMsgType[GotSignal].signal shouldBe a[Terminated]
    }

    "handle immediately stopping on start" in {
      val probe = TestProbe[AnyRef]()
      val delayed = spawn(DelayedStart.delayStart(Behaviors.stopped))
      val watcher = spawn(Behaviors.deferred[AnyRef] { ctx ⇒
        ctx.watch(delayed)
        probe.ref ! "watching"
        Behaviors.onSignal {
          case (_, s) ⇒
            probe.ref ! GotSignal(s)
            Behaviors.same
        }
      })
      probe.expectMsg("watching")
      probe.expectNoMessage(200.millis)
      delayed ! DelayedStart.Start
      probe.expectMsgType[GotSignal].signal shouldBe a[Terminated]
    }

    "handle deferred stopping on start" in {
      val probe = TestProbe[AnyRef]()
      val delayed = spawn(DelayedStart.delayStart[AnyRef](Behaviors.deferred { ctx ⇒
        probe.ref ! "stopping"
        Behaviors.stopped
      }))
      val watcher = spawn(Behaviors.deferred[AnyRef] { ctx ⇒
        ctx.watch(delayed)
        probe.ref ! "watching"
        Behaviors.onSignal {
          case (_, s) ⇒
            probe.ref ! GotSignal(s)
            Behaviors.same
        }
      })
      probe.expectMsg("watching")
      delayed ! DelayedStart.Start
      probe.expectMsg("stopping")
      probe.expectMsgType[GotSignal].signal shouldBe a[Terminated]
    }

  }

}
