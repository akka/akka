/**
 * Copyright (C) 2009-2018 Lightbend Inc. <https://www.lightbend.com>
 */

package akka.actor.typed.scaladsl

import akka.Done
import akka.actor.testkit.typed.TE
import akka.actor.testkit.typed.scaladsl.ActorTestKit
import akka.actor.typed
import akka.actor.typed.{ Behavior, BehaviorInterceptor, PostStop, Signal, TypedAkkaSpecWithShutdown }

import scala.concurrent.Promise

class StopSpec extends ActorTestKit with TypedAkkaSpecWithShutdown {
  import BehaviorInterceptor._

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
        Behaviors.intercept(
          new BehaviorInterceptor[AnyRef, AnyRef] {
            override def aroundReceive(ctx: typed.ActorContext[AnyRef], msg: AnyRef, target: ReceiveTarget[AnyRef]): Behavior[AnyRef] = {
              target(ctx, msg)
            }

            override def aroundSignal(ctx: typed.ActorContext[AnyRef], signal: Signal, target: SignalTarget[AnyRef]): Behavior[AnyRef] = {
              target(ctx, signal)
            }
          }
        )(Behaviors.stopped[AnyRef](Behaviors.receiveSignal[AnyRef] {
            case (ctx, PostStop) ⇒
              sawSignal.success(Done)
              Behaviors.empty
          }))
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
