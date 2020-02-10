/*
 * Copyright (C) 2017-2020 Lightbend Inc. <https://www.lightbend.com>
 */

package akka.actor.typed

import akka.actor.typed.internal.adapter.ActorSystemAdapter
import akka.actor.typed.scaladsl.AskPattern._
import akka.actor.typed.scaladsl.Behaviors
import akka.actor.typed.scaladsl.Behaviors._
import akka.actor.testkit.typed.scaladsl.TestProbe
import akka.util.Timeout
import scala.concurrent.duration._
import scala.concurrent.{ ExecutionContext, TimeoutException }
import scala.util.Success

import akka.actor.testkit.typed.scaladsl.ScalaTestWithActorTestKit
import scala.concurrent.Future

import akka.actor.DeadLetter
import akka.actor.UnhandledMessage
import akka.actor.testkit.typed.scaladsl.LoggingTestKit
import akka.actor.testkit.typed.scaladsl.LogCapturing
import akka.actor.typed.eventstream.EventStream
import org.scalatest.wordspec.AnyWordSpecLike

object AskSpec {
  sealed trait Msg
  final case class Foo(s: String, replyTo: ActorRef[String]) extends Msg
  final case class Stop(replyTo: ActorRef[Unit]) extends Msg
}

class AskSpec extends ScalaTestWithActorTestKit with AnyWordSpecLike with LogCapturing {

  import AskSpec._

  implicit def executor: ExecutionContext =
    system.executionContext

  val behavior: Behavior[Msg] = receive[Msg] {
    case (_, foo: Foo) =>
      foo.replyTo ! "foo"
      Behaviors.same
    case (_, Stop(r)) =>
      r ! (())
      Behaviors.stopped
  }

  "Ask pattern" must {
    "fail the future if the actor is already terminated" in {
      val ref = spawn(behavior)
      val stopResult: Future[Unit] = ref.ask(Stop)
      stopResult.futureValue

      val probe = createTestProbe()
      probe.expectTerminated(ref, probe.remainingOrDefault)
      val answer: Future[String] = ref.ask(Foo("bar", _))
      val result = answer.failed.futureValue
      result shouldBe a[TimeoutException]
      result.getMessage should include("had already been terminated.")
    }

    "succeed when the actor is alive" in {
      val ref = spawn(behavior)
      val response: Future[String] = ref.ask(Foo("bar", _))
      response.futureValue should ===("foo")
    }

    "provide a symbolic alias that works the same" in {
      val ref = spawn(behavior)
      val response: Future[String] = ref ? (Foo("bar", _))
      response.futureValue should ===("foo")
    }

    "fail the future if the actor doesn't reply in time" in {
      val unhandledProbe = createTestProbe[UnhandledMessage]()
      system.eventStream ! EventStream.Subscribe(unhandledProbe.ref)

      val actor = spawn(Behaviors.empty[Foo])
      implicit val timeout: Timeout = 10.millis

      val answer: Future[String] = actor.ask(Foo("bar", _))
      unhandledProbe.receiveMessage()
      val result = answer.failed.futureValue
      result shouldBe a[TimeoutException]
      result.getMessage should startWith("Ask timed out on")
    }

    "fail the future if the actor doesn't exist" in {
      val noSuchActor: ActorRef[Msg] = system match {
        case adaptedSys: ActorSystemAdapter[_] =>
          import akka.actor.typed.scaladsl.adapter._
          adaptedSys.system.provider.resolveActorRef("/foo/bar")
        case _ =>
          fail("this test must only run in an adapted actor system")
      }

      val deadLetterProbe = createTestProbe[DeadLetter]()
      system.eventStream ! EventStream.Subscribe(deadLetterProbe.ref)

      val answer: Future[String] = noSuchActor.ask(Foo("bar", _))
      val result = answer.failed.futureValue
      result shouldBe a[TimeoutException]
      result.getMessage should include("had already been terminated")

      val deadLetter = deadLetterProbe.receiveMessage()
      deadLetter.message match {
        case Foo(s, _) => s should ===("bar")
        case _         => fail(s"unexpected DeadLetter: $deadLetter")
      }
    }

    "transform a replied akka.actor.Status.Failure to a failed future" in {
      // It's unlikely but possible that this happens, since the receiving actor would
      // have to accept a message with an actoref that accepts AnyRef or be doing crazy casting
      // For completeness sake though
      implicit val classicSystem = akka.actor.ActorSystem("AskSpec-classic-1")
      try {
        case class Ping(respondTo: ActorRef[AnyRef])
        val ex = new RuntimeException("not good!")

        class LegacyActor extends akka.actor.Actor {
          def receive = {
            case Ping(respondTo) => respondTo ! akka.actor.Status.Failure(ex)
          }
        }

        val legacyActor = classicSystem.actorOf(akka.actor.Props(new LegacyActor))

        import scaladsl.AskPattern._
        import akka.actor.typed.scaladsl.adapter._
        implicit val timeout: Timeout = 3.seconds
        val typedLegacy: ActorRef[AnyRef] = legacyActor
        (typedLegacy.ask(Ping)).failed.futureValue should ===(ex)
      } finally {
        akka.testkit.TestKit.shutdownActorSystem(classicSystem)
      }
    }

    "fail asking actor if responder function throws" in {
      case class Question(reply: ActorRef[Long])

      val probe = TestProbe[AnyRef]("probe")
      val behv =
        Behaviors.receive[String] {
          case (context, "start-ask") =>
            context.ask[Question, Long](probe.ref, Question(_)) {
              case Success(42L) =>
                throw new RuntimeException("Unsupported number")
              case _ => "test"
            }
            Behaviors.same
          case (_, "test") =>
            probe.ref ! "got-test"
            Behaviors.same
          case (_, "get-state") =>
            probe.ref ! "running"
            Behaviors.same
          case (_, _) =>
            Behaviors.unhandled
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

      LoggingTestKit
        .error("Exception thrown out of adapter. Stopping myself.")
        .expect {
          replyRef2 ! 42L
        }(system)

      probe.expectTerminated(ref, probe.remainingOrDefault)
    }
  }
}
