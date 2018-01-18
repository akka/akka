/**
 * Copyright (C) 2018 Lightbend Inc. <https://www.lightbend.com>
 */
package akka.actor.typed
package scaladsl

import akka.testkit.typed.TestKit
import akka.testkit.typed.scaladsl.TestProbe

class StashSpec extends TestKit with TypedAkkaSpecWithShutdown {

  "Stashing" must {

    "be supported with immutable behavior" in {
      def active(processed: Vector[String]): Behavior[Command] =
        Behaviors.immutable { (ctx, cmd) ⇒
          cmd match {
            case msg: Msg ⇒
              active(processed :+ msg.s)
            case GetProcessed(replyTo) ⇒
              replyTo ! processed
              Behaviors.same
            case Stash ⇒
              stashing(ImmutableStashBuffer.empty(10), processed)
            case UnstashAll ⇒
              Behaviors.unhandled
          }
        }

      def stashing(buffer: ImmutableStashBuffer[Command], processed: Vector[String]): Behavior[Command] =
        Behaviors.immutable { (ctx, cmd) ⇒
          cmd match {
            case msg: Msg ⇒
              stashing(buffer :+ msg, processed)
            case GetProcessed(replyTo) ⇒
              replyTo ! processed
              Behaviors.same
            case UnstashAll ⇒
              buffer.unstashAll(ctx, active(processed))
            case Stash ⇒
              Behaviors.unhandled
          }
        }

      val actor = spawn(active(Vector.empty))
      val probe = TestProbe[Vector[String]]("probe")

      actor ! Msg("a")
      actor ! Msg("b")
      actor ! Msg("c")

      actor ! Stash
      actor ! Msg("d")
      actor ! GetProcessed(probe.ref)
      probe.expectMsg(Vector("a", "b", "c"))

      actor ! Msg("e")
      actor ! Msg("f")

      actor ! UnstashAll
      actor ! GetProcessed(probe.ref)
      probe.expectMsg(Vector("a", "b", "c", "d", "e", "f"))
    }

  }

  private sealed trait Command
  private final case class Msg(s: String) extends Command
  private case object Stash extends Command
  private case object UnstashAll extends Command
  private final case class GetProcessed(replyTo: ActorRef[Vector[String]]) extends Command
}
