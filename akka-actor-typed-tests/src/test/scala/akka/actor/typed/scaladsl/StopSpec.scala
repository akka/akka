/**
 * Copyright (C) 2009-2018 Lightbend Inc. <https://www.lightbend.com>
 */

package akka.actor.typed.scaladsl

import akka.Done
import akka.actor.testkit.typed.TE
import akka.actor.testkit.typed.scaladsl.{ ActorTestKit, TestProbe }
import akka.actor.typed.{ Behavior, PostStop, SupervisorStrategy, Terminated, TypedAkkaSpecWithShutdown }
import akka.testkit.EventFilter

import scala.concurrent.Promise

class StopSpec extends ActorTestKit with TypedAkkaSpecWithShutdown {

  "Stopping an actor" should {

    "execute the post stop" in {
      val sawSignal = Promise[Done]()
      spawn(Behaviors.setup[AnyRef] { _ ⇒
        Behaviors.stopped[AnyRef](Behaviors.receiveSignal[AnyRef] {
          case (ctx, PostStop) ⇒
            sawSignal.success(Done)
            Behaviors.empty
        })
      })
      sawSignal.future.futureValue should ===(Done)
    }

    // #25082
    "execute the post stop when wrapped" in {
      val sawSignal = Promise[Done]()
      val ref = spawn(Behaviors.setup[AnyRef] { _ ⇒
        Behaviors.tap(
          Behaviors.stopped[AnyRef](Behaviors.receiveSignal[AnyRef] {
            case (ctx, PostStop) ⇒
              sawSignal.success(Done)
              Behaviors.empty
          }))(
            (_, _) ⇒ (),
            (_, _) ⇒ ()
          )
      })
      ref ! "stopit"
      sawSignal.future.futureValue should ===(Done)
    }

    // #25096
    "execute the post stop early" in {
      val sawSignal = Promise[Done]()
      spawn(Behaviors.stopped[AnyRef](Behaviors.receiveSignal[AnyRef] {
        case (ctx, PostStop) ⇒
          sawSignal.success(Done)
          Behaviors.empty
      }))

      sawSignal.future.futureValue should ===(Done)
    }

  }

  "PostStop" should {
    "immediately throw when a deferred behavior (setup) is passed in as postStop" in {
      val ex = intercept[IllegalArgumentException] {
        Behaviors.stopped(
          // illegal:
          Behaviors.setup[String] { _ ⇒
            throw TE("boom!")
          }
        )
      }

      ex.getMessage should include("Behavior used as `postStop` behavior in Stopped(...) was a deferred one ")
    }
  }

}
