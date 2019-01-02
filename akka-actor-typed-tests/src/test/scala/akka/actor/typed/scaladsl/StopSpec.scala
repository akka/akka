/*
 * Copyright (C) 2009-2019 Lightbend Inc. <https://www.lightbend.com>
 */

package akka.actor.typed.scaladsl

import scala.concurrent.Promise
import akka.Done
import akka.actor.testkit.typed.TE
import akka.actor.testkit.typed.scaladsl.ScalaTestWithActorTestKit
import akka.actor.typed
import akka.actor.typed.Behavior
import akka.actor.typed.BehaviorInterceptor
import akka.actor.typed.PostStop
import akka.actor.typed.Signal
import org.scalatest.WordSpecLike

class StopSpec extends ScalaTestWithActorTestKit with WordSpecLike {
  import BehaviorInterceptor._

  "Stopping an actor" should {

    "execute the post stop" in {
      val sawSignal = Promise[Done]()
      spawn(Behaviors.setup[AnyRef] { _ ⇒
        Behaviors.stopped[AnyRef](Behaviors.receiveSignal[AnyRef] {
          case (context, PostStop) ⇒
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
            override def aroundReceive(context: typed.TypedActorContext[AnyRef], message: AnyRef, target: ReceiveTarget[AnyRef]): Behavior[AnyRef] = {
              target(context, message)
            }

            override def aroundSignal(context: typed.TypedActorContext[AnyRef], signal: Signal, target: SignalTarget[AnyRef]): Behavior[AnyRef] = {
              target(context, signal)
            }
          }
        )(Behaviors.stopped[AnyRef](Behaviors.receiveSignal[AnyRef] {
            case (context, PostStop) ⇒
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
        case (context, PostStop) ⇒
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
