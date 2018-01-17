/**
 * Copyright (C) 2009-2018 Lightbend Inc. <http://www.lightbend.com>
 */
package akka.actor.typed.scaladsl

import akka.actor.typed.{ ActorRef, TypedAkkaSpec }
import akka.testkit.typed.TestKit
import akka.testkit.typed.scaladsl.TestProbe

import scala.util.{ Failure, Success }

class ScalaDslActorContextSpec extends TestKit with TypedAkkaSpec {

  "The Scala DSL ActorContext" must {

    "provide a safe ask" in {
      case class Ping(sender: ActorRef[Pong])
      case class Pong(self: String)
      val pingPong = spawn(Actor.immutable[Ping] { (ctx, msg) ⇒
        msg.sender ! Pong(ctx.self.path.name)
        Actor.same
      }, "ping-pong")
      val probe = TestProbe[AnyRef]()

      val snitch = Actor.deferred[Pong] { (ctx) ⇒

        // Timeout comes from TypedAkkaSpec

        ctx.ask(pingPong, Ping) {
          // TODO this doesn't verify that we executed on this actor thread
          case Success(pong) ⇒ Pong(ctx.self.path.name + "1")
          // TODO could we have an even shorter variant that just maps success and throws on error
          case Failure(ex)   ⇒ throw ex
        }

        import ctx.Ask

        // FIXME needs the dot to work - alternatives?
        pingPong.?(Ping) {
          // TODO this doesn't verify that we executed on this actor thread
          case Success(pong) ⇒ Pong(ctx.self.path.name + "2")
          // TODO could we have an even shorter variant that just maps success and throws on error
          case Failure(ex)   ⇒ throw ex
        }
        Actor.immutable {
          case (ctx, pong: Pong) ⇒
            probe.ref ! pong
            Actor.same
        }
      }

      spawn(snitch, "snitch")

      val names = Set(probe.expectMsgType[Pong].self, probe.expectMsgType[Pong].self)

      names should ===(Set("snitch1", "snitch2"))
    }
    // FIXME we should have a test over remoting as well in typed-cluster

  }

}
