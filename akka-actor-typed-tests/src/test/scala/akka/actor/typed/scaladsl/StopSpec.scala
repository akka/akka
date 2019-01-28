/*
 * Copyright (C) 2009-2019 Lightbend Inc. <https://www.lightbend.com>
 */

package akka.actor.typed.scaladsl

import scala.concurrent.Promise

import akka.Done
import akka.actor.testkit.typed.TestException
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
        Behaviors.stopped { () ⇒
          sawSignal.success(Done)
        }
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
        )(Behaviors.stopped { () ⇒
            sawSignal.success(Done)
          })
      })
      ref ! "stopit"
      sawSignal.future.futureValue should ===(Done)
    }

    // #25096
    "execute the post stop early" in {
      val sawSignal = Promise[Done]()
      spawn(Behaviors.stopped { () ⇒
        sawSignal.success(Done)
      })

      sawSignal.future.futureValue should ===(Done)
    }

  }
}
