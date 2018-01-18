/**
 * Copyright (C) 2017-2018 Lightbend Inc. <https://www.lightbend.com>
 */
package akka.actor.typed

import akka.actor.typed.internal.adapter.ActorSystemAdapter
import akka.actor.typed.scaladsl.Behaviors
import akka.actor.typed.scaladsl.Behaviors._
import akka.actor.typed.scaladsl.AskPattern._
import akka.pattern.AskTimeoutException
import akka.testkit.typed.TestKit
import org.scalatest.concurrent.ScalaFutures

import scala.concurrent.ExecutionContext

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
      answer.recover { case _: AskTimeoutException ⇒ "ask" }.futureValue should ===("ask")
    }

    "must succeed when the actor is alive" in {
      val ref = spawn(behavior)
      val response = ref ? Foo("bar")
      response.futureValue should ===("foo")
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
      answer.recover { case _: AskTimeoutException ⇒ "ask" }.futureValue should ===("ask")
    }
  }
}
