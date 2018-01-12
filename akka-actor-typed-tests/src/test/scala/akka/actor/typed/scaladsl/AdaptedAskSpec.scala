/**
 * Copyright (C) 2009-2018 Lightbend Inc. <http://www.lightbend.com>
 */
package akka.actor.typed.scaladsl

import akka.actor.typed.{ ActorRef, TypedAkkaSpec, TypedAkkaSpecWithShutdown }
import akka.testkit.typed.TestKit
import akka.testkit.typed.scaladsl.TestProbe
import akka.util.Timeout

import scala.concurrent.duration._
import scala.util.{ Failure, Success }

class AdaptedAskSpec extends TestKit() with TypedAkkaSpecWithShutdown {

  sealed trait Protocol

  sealed trait OtherActorProtocol
  case object OtherActorPong
  case class OtherActorPing(respondTo: ActorRef[OtherActorPong.type]) extends OtherActorProtocol

  sealed trait ActorProtocol
  case class TriggerPing(id: Long) extends ActorProtocol
  case class GotPong(id: Long) extends ActorProtocol
  case class PongTimedOut(id: Long) extends ActorProtocol

  "Asking another actor" should {

    "work just fine" in {

      val otherActor = spawn(Actor.immutable[OtherActorProtocol] { (ctx, msg) ⇒
        msg match {
          case OtherActorPing(respondTo) ⇒
            respondTo ! OtherActorPong
            Actor.same
        }
      })

      val probe = TestProbe[AnyRef]()

      implicit val timeout = Timeout(1.second)
      val actor = spawn(Actor.immutable[ActorProtocol] { (ctx, msg) ⇒

        msg match {
          case TriggerPing(id) ⇒
            ctx.ask(otherActor, OtherActorPing) {
              case Success(OtherActorPong) ⇒ GotPong(id)
              case Failure(_)              ⇒ PongTimedOut(id)
            }
            Actor.same

          case m: GotPong ⇒
            probe.ref ! m
            Actor.same

          case m: PongTimedOut ⇒
            probe.ref ! m
            Actor.same
        }

      })

      actor ! TriggerPing(1)
      probe.expectMsg(GotPong(1))
    }

    "fail with a timeout" in {

      val otherActor = spawn(Actor.ignore[OtherActorProtocol])

      val probe = TestProbe[AnyRef]()

      implicit val timeout = Timeout(20.millis)
      val actor = spawn(Actor.immutable[ActorProtocol] { (ctx, msg) ⇒

        msg match {
          case TriggerPing(id) ⇒
            ctx.ask(otherActor, OtherActorPing) {
              case Success(OtherActorPong) ⇒ GotPong(id)
              case Failure(_)              ⇒ PongTimedOut(id)
            }
            Actor.same

          case m: GotPong ⇒
            probe.ref ! m
            Actor.same

          case m: PongTimedOut ⇒
            probe.ref ! m
            Actor.same
        }

      })

      actor ! TriggerPing(1)
      probe.expectMsg(PongTimedOut(1))
    }

  }

}
