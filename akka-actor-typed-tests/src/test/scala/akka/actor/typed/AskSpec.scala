/*
 * Copyright (C) 2017-2019 Lightbend Inc. <https://www.lightbend.com>
 */

package akka.actor.typed

import akka.actor.typed.internal.adapter.ActorSystemAdapter
import akka.actor.typed.scaladsl.AskPattern._
import akka.actor.typed.scaladsl.Behaviors
import akka.actor.typed.scaladsl.Behaviors._
import akka.testkit.EventFilter
import akka.actor.testkit.typed.scaladsl.TestProbe
import akka.util.Timeout

import scala.concurrent.duration._
import scala.concurrent.{ ExecutionContext, TimeoutException }
import scala.util.Success
import akka.actor.testkit.typed.scaladsl.ScalaTestWithActorTestKit
import org.scalatest.WordSpecLike

object AskSpec {
  sealed trait Msg
  final case class Foo(s: String)(val replyTo: ActorRef[String]) extends Msg
  final case class Stop(replyTo: ActorRef[Unit]) extends Msg
}

class AskSpec extends ScalaTestWithActorTestKit("""
  akka.loglevel=warning
  akka.loggers = [ akka.testkit.TestEventListener ]
  """) with WordSpecLike {

  // FIXME eventfilter support in typed testkit
  import AskSpec._

  // FIXME eventfilter support in typed testkit
  import scaladsl.adapter._
  implicit val untypedSystem = system.toUntyped

  implicit def executor: ExecutionContext =
    system.executionContext

  val behavior: Behavior[Msg] = receive[Msg] {
    case (_, foo: Foo) ⇒
      foo.replyTo ! "foo"
      Behaviors.same
    case (_, Stop(r)) ⇒
      r ! ()
      Behaviors.stopped
  }

  "Ask pattern" must {
    "fail the future if the actor is already terminated" in {
      val ref = spawn(behavior)
      (ref ? Stop).futureValue
      val probe = createTestProbe()
      probe.expectTerminated(ref, probe.remainingOrDefault)
      val answer =
        EventFilter.warning(pattern = ".*received dead letter.*", occurrences = 1).intercept {
          ref ? Foo("bar")
        }
      val result = answer.failed.futureValue
      result shouldBe a[TimeoutException]
      result.getMessage should include("had already been terminated.")
    }

    "succeed when the actor is alive" in {
      val ref = spawn(behavior)
      val response = ref ? Foo("bar")
      response.futureValue should ===("foo")
    }

    "fail the future if the actor doesn't reply in time" in {
      val actor = spawn(Behaviors.empty[Foo])
      implicit val timeout: Timeout = 10.millis
      EventFilter.warning(pattern = ".*unhandled message.*", occurrences = 1).intercept {
        val answer = actor ? Foo("bar")
        val result = answer.failed.futureValue
        result shouldBe a[TimeoutException]
        result.getMessage should startWith("Ask timed out on")
      }
    }

    /** See issue #19947 (MatchError with adapted ActorRef) */
    "fail the future if the actor doesn't exist" in {
      val noSuchActor: ActorRef[Msg] = system match {
        case adaptedSys: ActorSystemAdapter[_] ⇒
          import akka.actor.typed.scaladsl.adapter._
          adaptedSys.untyped.provider.resolveActorRef("/foo/bar")
        case _ ⇒
          fail("this test must only run in an adapted actor system")
      }

      val answer =
        EventFilter.warning(pattern = ".*received dead letter.*", occurrences = 1).intercept {
          noSuchActor ? Foo("bar")
        }
      val result = answer.failed.futureValue
      result shouldBe a[TimeoutException]
      result.getMessage should include("had already been terminated")
    }

    "transform a replied akka.actor.Status.Failure to a failed future" in {
      // It's unlikely but possible that this happens, since the receiving actor would
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

    "fail asking actor if responder function throws" in {
      case class Question(reply: ActorRef[Long])

      val probe = TestProbe[AnyRef]("probe")
      val behv =
        Behaviors.receive[String] {
          case (context, "start-ask") ⇒
            context.ask[Question, Long](probe.ref)(Question(_)) {
              case Success(42L) ⇒
                throw new RuntimeException("Unsupported number")
              case _ ⇒ "test"
            }
            Behavior.same
          case (_, "test") ⇒
            probe.ref ! "got-test"
            Behavior.same
          case (_, "get-state") ⇒
            probe.ref ! "running"
            Behavior.same
          case (_, _) ⇒
            Behavior.unhandled
        }

      val ref = spawn(behv)

      ref ! "test"
      probe.expectMessage("got-test")

      ref ! "start-ask"
      val Question(replyRef) = probe.expectMessageType[Question]
      replyRef ! 0L
      probe.expectMessage("got-test")

      ref ! "start-ask"
      val Question(replyRef2) = probe.expectMessageType[Question]

      EventFilter[RuntimeException](message = "Exception thrown out of adapter. Stopping myself.", occurrences = 1).intercept {
        replyRef2 ! 42L
      }(system.toUntyped)

      probe.expectTerminated(ref, probe.remainingOrDefault)
    }
  }
}
