/**
 * Copyright (C) 2017 Lightbend Inc. <http://www.lightbend.com>
 */
package akka.typed

import scala.concurrent.ExecutionContext
import scala.concurrent.duration._
import org.scalatest.concurrent.ScalaFutures
import akka.util.Timeout
import akka.pattern.AskTimeoutException
import akka.typed.scaladsl.Actor._
import akka.typed.scaladsl.AskPattern._

object AskSpec {

  sealed trait Msg
  final case class Foo(s: String)(val replyTo: ActorRef[String]) extends Msg
  final case class Stop(replyTo: ActorRef[Unit]) extends Msg
}

class AskSpec extends TypedSpec with ScalaFutures {

  import AskSpec._

  trait Common {

    def system: ActorSystem[TypedSpec.Command]

    implicit def executor: ExecutionContext =
      system.executionContext

    val behavior: Behavior[Msg] = immutable[Msg] {
      case (_, foo: Foo) ⇒
        foo.replyTo ! "foo"
        same
      case (_, Stop(r)) ⇒
        r ! (())
        stopped
    }

    def `must fail the future if the actor is already terminated`(): Unit = {
      val fut = for {
        ref ← system ? TypedSpec.Create(behavior, "test1")
        _ ← ref ? Stop
        answer ← ref.?(Foo("bar"))(Timeout(1.second), implicitly)
      } yield answer
      (fut.recover { case _: AskTimeoutException ⇒ "" }).futureValue should ===("")
    }

    def `must succeed when the actor is alive`(): Unit = {
      val fut = for {
        ref ← system ? TypedSpec.Create(behavior, "test2")
        answer ← ref ? Foo("bar")
      } yield answer
      fut.futureValue should ===("foo")
    }
  }

  object `Ask pattern (native)` extends Common with NativeSystem

  object `Ask pattern (adapted)` extends Common with AdaptedSystem {

    import AskSpec._

    /** See issue #19947 (MatchError with adapted ActorRef) */
    def `must fail the future if the actor doesn't exist`(): Unit = {
      val noSuchActor: ActorRef[Msg] = system match {
        case adaptedSys: akka.typed.internal.adapter.ActorSystemAdapter[_] ⇒
          import akka.typed.scaladsl.adapter._
          adaptedSys.untyped.provider.resolveActorRef("/foo/bar")
        case _ ⇒
          fail("this test must only run in an adapted actor system")
      }
      val fut = for {
        answer ← noSuchActor.?(Foo("bar"))(Timeout(1.second), implicitly)
      } yield answer
      (fut.recover { case _: AskTimeoutException ⇒ "" }).futureValue should ===("")
    }
  }
}
