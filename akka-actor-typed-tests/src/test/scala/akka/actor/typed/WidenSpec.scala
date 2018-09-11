/**
 * Copyright (C) 2018 Lightbend Inc. <https://www.lightbend.com>
 */

package akka.actor.typed

import akka.actor.testkit.typed.scaladsl.ActorTestKit
import akka.actor.testkit.typed.scaladsl.TestProbe
import akka.actor.typed.scaladsl.Behaviors
import org.scalatest.concurrent.ScalaFutures

object WidenSpec {
  final case class Msg1(hello: String, replyTo: ActorRef[String])
  final case class Msg2(hello: String, replyTo: ActorRef[String])
}

class WidenSpec extends ActorTestKit
  with TypedAkkaSpecWithShutdown with ScalaFutures {
  override def name = "WidenSpec"

  import WidenSpec._

  "Widen" must {
    "be useful for transforming and filtering messages" in {

      def inner(count: Int): Behavior[Msg2] = Behaviors.receiveMessage {
        case Msg2(hello, replyTo) ⇒
          replyTo ! s"$hello-$count"
          inner(count + 1)
      }

      def outer: Behavior[Msg1] = inner(0).widen[Msg1] {
        case Msg1(hello, replyTo) if hello != "unsupported" ⇒ Msg2(hello, replyTo)
      }

      val ref = spawn(outer)
      val probe = TestProbe[String]()
      ref ! Msg1("hello", probe.ref)
      probe.expectMessage("hello-0")
      ref ! Msg1("unsupported", probe.ref)
      probe.expectNoMessage()
      ref ! Msg1("hello", probe.ref)
      probe.expectMessage("hello-1")
    }

  }
}
