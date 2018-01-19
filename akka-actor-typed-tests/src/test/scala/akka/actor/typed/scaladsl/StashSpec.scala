/**
 * Copyright (C) 2018 Lightbend Inc. <https://www.lightbend.com>
 */
package akka.actor.typed
package scaladsl

import akka.event.LoggingAdapter
import akka.testkit.typed.TestKit
import akka.testkit.typed.scaladsl.TestProbe

object StashSpec {
  sealed trait Command
  final case class Msg(s: String) extends Command
  final case class Unstashed(cmd: Command) extends Command

  case object Stash extends Command
  case object UnstashAll extends Command
  case object Unstash extends Command
  final case class GetProcessed(replyTo: ActorRef[Vector[String]]) extends Command

  // FIXME replace when we get the logging in place, #23326
  def log(ctx: ActorContext[_]): LoggingAdapter = ctx.system.log

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
        case Unstash ⇒
          Behaviors.unhandled
        case u: Unstashed ⇒
          throw new IllegalStateException(s"Unexpected $u in active")
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
        case Unstash ⇒
          log(ctx).debug(s"Unstash ${buffer.size}")
          if (buffer.isEmpty)
            active(processed)
          else {
            ctx.self ! Unstash // continue unstashing until buffer is empty
            val numberOfMessages = 2
            log(ctx).debug(s"Unstash $numberOfMessages of ${buffer.size}, starting with ${buffer.head}")
            buffer.unstash(ctx, unstashing(buffer.drop(numberOfMessages), processed), numberOfMessages, Unstashed)
          }
        case Stash ⇒
          Behaviors.unhandled
        case u: Unstashed ⇒
          throw new IllegalStateException(s"Unexpected $u in stashing")
      }
    }

  def unstashing(buffer: ImmutableStashBuffer[Command], processed: Vector[String]): Behavior[Command] =
    Behaviors.immutable { (ctx, cmd) ⇒
      cmd match {
        case Unstashed(msg: Msg) ⇒
          log(ctx).debug(s"unstashed $msg")
          unstashing(buffer, processed :+ msg.s)
        case Unstashed(GetProcessed(replyTo)) ⇒
          log(ctx).debug(s"unstashed GetProcessed")
          replyTo ! processed
          Behaviors.same
        case msg: Msg ⇒
          log(ctx).debug(s"got $msg in unstashing")
          unstashing(buffer :+ msg, processed)
        case get: GetProcessed ⇒
          log(ctx).debug(s"got GetProcessed in unstashing")
          unstashing(buffer :+ get, processed)
        case Stash ⇒
          stashing(buffer, processed)
        case Unstash ⇒
          if (buffer.isEmpty) {
            log(ctx).debug(s"unstashing done")
            active(processed)
          } else {
            ctx.self ! Unstash // continue unstashing until buffer is empty
            val numberOfMessages = 2
            log(ctx).debug(s"Unstash $numberOfMessages of ${buffer.size}, starting with ${buffer.head}")
            buffer.unstash(ctx, unstashing(buffer.drop(numberOfMessages), processed), numberOfMessages, Unstashed)
          }
        case UnstashAll ⇒
          Behaviors.unhandled
        case u: Unstashed ⇒
          throw new IllegalStateException(s"Unexpected $u in unstashing")
      }
    }

}

class StashSpec extends TestKit with TypedAkkaSpecWithShutdown {
  import StashSpec._

  "Stashing with immutable behavior" must {

    "support unstash all" in {
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

    "support unstash a few at a time" in {
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
      // now 3 messages are stashed

      actor ! Unstash
      actor ! Msg("g") // might arrive in the middle of the unstashing
      actor ! GetProcessed(probe.ref) // this is also stashed until all unstashed
      probe.expectMsg(Vector("a", "b", "c", "d", "e", "f", "g"))
    }

  }

}
