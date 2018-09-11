/**
 * Copyright (C) 2018 Lightbend Inc. <https://www.lightbend.com>
 */

package akka.actor.typed

import akka.actor.testkit.typed.scaladsl.ActorTestKit
import akka.actor.testkit.typed.scaladsl.TestProbe
import akka.actor.typed.scaladsl.Behaviors
import org.scalatest.concurrent.ScalaFutures

object PreSpec {
  final case class Msg(hello: String, replyTo: ActorRef[String])
  case object MyPoisonPill
}

class PreSpec extends ActorTestKit
  with TypedAkkaSpecWithShutdown with ScalaFutures {
  override def name = "WidenSpec"

  import PreSpec._

  "Pre" must {
    "be useful for implementing PoisonPill" in {

      def inner(count: Int): Behavior[Msg] = Behaviors.receiveMessage {
        case Msg(hello, replyTo) ⇒
          replyTo ! s"$hello-$count"
          inner(count + 1)
      }

      val poisonInterceptor: Behavior[Any] = Behaviors.receiveMessagePartial[Any] {
        case MyPoisonPill ⇒ Behaviors.stopped
      }

      // FIXME can we simplify this widen - narrow dance
      val decorated: Behavior[Msg] =
        Behaviors.pre(poisonInterceptor, inner(0).widen[Any] { case m: Msg ⇒ m }).narrow

      val ref = spawn(decorated)
      val probe = TestProbe[String]()
      ref ! Msg("hello", probe.ref)
      probe.expectMessage("hello-0")
      ref ! Msg("hello", probe.ref)
      probe.expectMessage("hello-1")

      ref.upcast[Any] ! MyPoisonPill

      probe.expectTerminated(ref, probe.remainingOrDefault)
    }

  }
}
