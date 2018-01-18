/**
 * Copyright (C) 2017-2018 Lightbend Inc. <https://www.lightbend.com>
 */
package akka.actor.typed

import akka.actor.typed.internal.adapter.ActorSystemAdapter
import akka.actor.typed.scaladsl.Behaviors
import akka.actor.typed.scaladsl.Behaviors._
import akka.actor.typed.scaladsl.AskPattern._
import akka.actor.typed.scaladsl.adapter._
import akka.pattern.AskTimeoutException
import akka.testkit.typed.TestKit
import akka.util.Timeout
import org.scalatest.concurrent.ScalaFutures

import scala.concurrent.duration._
import scala.concurrent.{ ExecutionContext, TimeoutException }

object AskSpec {
  sealed trait Msg
  final case class Foo(s: String)(val replyTo: ActorRef[String]) extends Msg
  final case class Stop(replyTo: ActorRef[Unit]) extends Msg
}

class AskSpec extends TestKit("AskSpec") with TypedAkkaSpec with ScalaFutures {

  import AskSpec._

  implicit def executor: ExecutionContext =
    system.executionContext

  val behavior: Behavior[Msg] = immutable[Msg] {
    case (_, foo: Foo) ⇒
      foo.replyTo ! "foo"
      Behaviors.same
    case (_, Stop(r)) ⇒
      r ! ()
      Behaviors.stopped
  }

  "Ask pattern" must {
    "must fail the future if the actor is already terminated" in {
      val ref = spawn(behavior)
      (ref ? Stop).futureValue
      val answer = ref ? Foo("bar")
      val result = answer.failed.futureValue
      result shouldBe a[TimeoutException]
      result.getMessage should include("had already been terminated.")
    }

    "must succeed when the actor is alive" in {
      val ref = spawn(behavior)
      val response = ref ? Foo("bar")
      response.futureValue should ===("foo")
    }

    "must fail the future if the actor doesn't reply in time" in {
      val actor = spawn(Behaviors.empty[Foo])
      implicit val timeout: Timeout = 10.millis
      val answer = actor ? Foo("bar")
      val result = answer.failed.futureValue
      result shouldBe a[TimeoutException]
      result.getMessage should startWith("Ask timed out on")
    }

    /** See issue #19947 (MatchError with adapted ActorRef) */
    "must fail the future if the actor doesn't exist" in {
      val noSuchActor: ActorRef[Msg] = system match {
        case adaptedSys: ActorSystemAdapter[_] ⇒
          import akka.actor.typed.scaladsl.adapter._
          adaptedSys.untyped.provider.resolveActorRef("/foo/bar")
        case _ ⇒
          fail("this test must only run in an adapted actor system")
      }

      val answer = noSuchActor ? Foo("bar")
      val result = answer.failed.futureValue
      result shouldBe a[TimeoutException]
      result.getMessage should include("had already been terminated")
    }

    "must transform a replied akka.actor.Status.Failure to a failed future" in {
      // It's unlikely but possible that this happens, since the recieving actor would
      // have to accept a message with an actoref that accepts AnyRef or be doing crazy casting
      // For completeness sake though
      implicit val untypedSystem = akka.actor.ActorSystem("AskSpec-untyped-1")
      try {
        case class Ping(respondTo: ActorRef[AnyRef])
        val ex = new RuntimeException("not good!")

        class LegacyActor extends akka.actor.Actor {
          def receive = {
            case Ping(respondTo) ⇒ respondTo ! akka.actor.Status.Failure(ex)
          }
        }

        val legacyActor = untypedSystem.actorOf(akka.actor.Props(new LegacyActor))

        import scaladsl.AskPattern._
        implicit val timeout: Timeout = 3.seconds
        implicit val scheduler = untypedSystem.toTyped.scheduler
        val typedLegacy: ActorRef[AnyRef] = legacyActor
        (typedLegacy ? Ping).failed.futureValue should ===(ex)
      } finally {
        akka.testkit.TestKit.shutdownActorSystem(untypedSystem)
      }
    }
  }
}
