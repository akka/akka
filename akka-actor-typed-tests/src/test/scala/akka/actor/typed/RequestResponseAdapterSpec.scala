/**
 * Copyright (C) 2009-2018 Lightbend Inc. <http://www.lightbend.com>
 */
package akka.actor.typed

import akka.actor.typed.scaladsl.Actor
import akka.pattern.AskTimeoutException
import akka.testkit.typed.scaladsl.TestProbe
import akka.util.Timeout
import akka.actor.typed.scaladsl.AskPattern._
import akka.testkit.typed.TestKitSettings

import scala.concurrent.duration._
import scala.reflect.ClassTag
import scala.util.{ Failure, Success, Try }

class RequestResponseAdapterSpec extends TypedSpec with StartSupport {

  sealed trait Protocol

  sealed trait OtherActorProtocol
  case object OtherActorPong
  case class OtherActorPing(respondTo: ActorRef[OtherActorPong.type]) extends OtherActorProtocol

  sealed trait ActorProtocol
  case class TriggerPing(id: Long) extends ActorProtocol
  case class GotPong(id: Long) extends ActorProtocol
  case class PongTimedOut(id: Long) extends ActorProtocol

  // I think this should go directly on the actor context, but to make experimentation easier I put it here
  implicit class ContextDecorator[T](ctx: akka.actor.typed.scaladsl.ActorContext[T]) {
    /**
     * Works like [[akka.actor.typed.scaladsl.ActorContext.spawnAdapter]] but limited to a single request-response
     * interaction with another typed actor.
     *
     * The interaction has a timeout (to avoid resource a resource leak). If
     * the timeout hits without any response it will be transformed to a message for this actor through the
     * `failToOwnProtocol` function.
     *
     * @param resToOwnProtocol Transforms the response back from the `otherActor` into a message this actor understands
     *                         can touch immutable state to provide a context for the interaction, an id for example, but
     *                         must not touch the `ActorContext` as it will not be executed inside of this actor.
     *
     * @tparam Req The request protocol, what the other actor accepts
     * @tparam Res The response protocol, what the other actor wants to send back
     */
    def adaptSingle[Req, Res](otherActor: ActorRef[Req], msgFactory: ActorRef[Res] ⇒ Req)(resToOwnProtocol: Try[Res] ⇒ T)(implicit timeout: Timeout, classTag: ClassTag[Res]): Unit = {

      implicit val ex = ctx.system.executionContext
      implicit val scheduler = ctx.system.scheduler

      (otherActor ? msgFactory)
        .mapTo[Res]
        .onComplete(t ⇒ ctx.self ! resToOwnProtocol(t))
    }

  }

  implicit val settings = TestKitSettings(system)

  "Single request response interaction adapting" should {

    "not be boilerplatey and work" in {

      val otherActor = start(Actor.immutable[OtherActorProtocol] { (ctx, msg) ⇒
        msg match {
          case OtherActorPing(respondTo) ⇒
            respondTo ! OtherActorPong
            Actor.same
        }
      })

      val probe = TestProbe[AnyRef]()

      implicit val timeout = Timeout(1.second)
      val actor = start(Actor.immutable[ActorProtocol] { (ctx, msg) ⇒

        msg match {
          case TriggerPing(id) ⇒
            // this is it
            // FIXME naming this is really hard: ideas `requestResponse`, `interact`, `query`, `ask`
            // FIXME can it be expressed in an even shorter way? (I don't see how but maybe I'm missing something)
            // note how it allows for capturing context with the id (also dangerous because could be abused closing
            // over ctx but no idea how to workaround that, we need a response => our-response function after all
            ctx.adaptSingle(otherActor, OtherActorPing) {
              case Success(OtherActorPong) ⇒ GotPong(id)
              case Failure(_)              ⇒ PongTimedOut(id)
            }
            Actor.same

          case m: GotPong ⇒
            probe.ref ! m
            Actor.same

          case m: PongTimedOut ⇒
            probe.ref ! PongTimedOut
            Actor.same
        }

      })

      actor ! TriggerPing(1)
      probe.expectMsg(GotPong(1))
    }

  }

}
