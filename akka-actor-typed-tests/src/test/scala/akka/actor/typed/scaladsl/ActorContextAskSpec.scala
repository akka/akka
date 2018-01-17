/**
 * Copyright (C) 2009-2018 Lightbend Inc. <http://www.lightbend.com>
 */
package akka.actor.typed.scaladsl

import akka.actor.typed.{ ActorRef, Props, TypedAkkaSpec }
import akka.pattern.AskTimeoutException
import akka.testkit.typed.TestKit
import akka.testkit.typed.scaladsl.TestProbe
import akka.util.Timeout
import com.typesafe.config.ConfigFactory

import scala.concurrent.duration._
import scala.reflect.ClassTag
import scala.util.{ Failure, Success }

object ActorContextAskSpec {
  val config = ConfigFactory.parseString(
    """
      ping-pong-dispatcher {
        executor = thread-pool-executor
        type = PinnedDispatcher
      }
      snitch-dispatcher {
        executor = thread-pool-executor
        type = PinnedDispatcher
      }
    """)
}

class ActorContextAskSpec extends TestKit(ActorContextAskSpec.config) with TypedAkkaSpec {

  "The Scala DSL ActorContext" must {

    // FIXME we should have a test over remoting as well in typed-cluster

    "provide a safe ask" in {
      case class Ping(sender: ActorRef[Pong])
      case class Pong(selfName: String, threadName: String)

      val pingPong = spawn(Actor.immutable[Ping] { (ctx, msg) ⇒
        msg.sender ! Pong(ctx.self.path.name, Thread.currentThread().getName)
        Actor.same
      }, "ping-pong", Props.empty.withDispatcherFromConfig("ping-pong-dispatcher"))

      val probe = TestProbe[AnyRef]()

      val snitch = Actor.deferred[Pong] { (ctx) ⇒

        // Timeout comes from TypedAkkaSpec

        ctx.ask(pingPong, Ping) {
          case Success(pong) ⇒ Pong(ctx.self.path.name + "1", Thread.currentThread().getName)
          case Failure(ex)   ⇒ throw ex
        }

        // FIXME it is nice that it is the actorref you ask but not sure it is justified with the implicit at all WDYT?
        import ctx.Ask
        pingPong.ask(Ping) {
          case Success(pong) ⇒ Pong(ctx.self.path.name + "2", Thread.currentThread().getName)
          case Failure(ex)   ⇒ throw ex
        }
        Actor.immutable {
          case (ctx, pong: Pong) ⇒
            probe.ref ! pong
            Actor.same
        }
      }

      spawn(snitch, "snitch", Props.empty.withDispatcherFromConfig("snitch-dispatcher"))

      val pongs = Set(probe.expectMsgType[Pong], probe.expectMsgType[Pong])

      pongs.map(_.selfName) should ===(Set("snitch1", "snitch2"))
      pongs.map(_.threadName).forall(_.startsWith("ActorContextAskSpec-snitch-dispatcher")) should ===(true)
    }

    "deal with timeouts in ask" in {
      // pending // ask timeout does not seem to work on adapted actor system

      val probe = TestProbe[AnyRef]()
      val snitch = Actor.deferred[AnyRef] { (ctx) ⇒

        ctx.ask[String, String](system.deadLetters, ref ⇒ "boo") {
          case Success(m) ⇒ m
          case Failure(x) ⇒ x
        }(20.millis, implicitly[ClassTag[String]])

        Actor.immutable {
          case (_, msg) ⇒
            probe.ref ! msg
            Actor.same
        }
      }

      spawn(snitch)

      probe.expectMsgType[AskTimeoutException]

    }

  }

}
