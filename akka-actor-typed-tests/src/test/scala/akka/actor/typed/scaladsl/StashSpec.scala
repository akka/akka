/**
 * Copyright (C) 2018 Lightbend Inc. <https://www.lightbend.com>
 */

package akka.actor.typed
package scaladsl

import akka.testkit.typed.scaladsl.{ ActorTestKit, TestProbe }

object StashSpec {
  sealed trait Command
  final case class Msg(s: String) extends Command
  final case class Unstashed(cmd: Command) extends Command

  case object Stash extends Command
  case object UnstashAll extends Command
  case object Unstash extends Command
  final case class GetProcessed(replyTo: ActorRef[Vector[String]]) extends Command
  final case class GetStashSize(replyTo: ActorRef[Int]) extends Command

  val immutableStash: Behavior[Command] =
    Behaviors.setup[Command] { _ ⇒
      val buffer = StashBuffer[Command](capacity = 10)

      def active(processed: Vector[String]): Behavior[Command] =
        Behaviors.receive { (ctx, cmd) ⇒
          cmd match {
            case msg: Msg ⇒
              active(processed :+ msg.s)
            case GetProcessed(replyTo) ⇒
              replyTo ! processed
              Behaviors.same
            case Stash ⇒
              stashing(processed)
            case GetStashSize(replyTo) ⇒
              replyTo ! 0
              Behaviors.same
            case UnstashAll ⇒
              Behaviors.unhandled
            case Unstash ⇒
              Behaviors.unhandled
            case u: Unstashed ⇒
              throw new IllegalStateException(s"Unexpected $u in active")
          }
        }

      def stashing(processed: Vector[String]): Behavior[Command] =
        Behaviors.receive { (ctx, cmd) ⇒
          cmd match {
            case msg: Msg ⇒
              buffer.stash(msg)
              Behaviors.same
            case g: GetProcessed ⇒
              buffer.stash(g)
              Behaviors.same
            case GetStashSize(replyTo) ⇒
              replyTo ! buffer.size
              Behaviors.same
            case UnstashAll ⇒
              buffer.unstashAll(ctx, active(processed))
            case Unstash ⇒
              ctx.log.debug(s"Unstash ${buffer.size}")
              if (buffer.isEmpty)
                active(processed)
              else {
                ctx.self ! Unstash // continue unstashing until buffer is empty
                val numberOfMessages = 2
                ctx.log.debug(s"Unstash $numberOfMessages of ${buffer.size}, starting with ${buffer.head}")
                buffer.unstash(ctx, unstashing(processed), numberOfMessages, Unstashed)
              }
            case Stash ⇒
              Behaviors.unhandled
            case u: Unstashed ⇒
              throw new IllegalStateException(s"Unexpected $u in stashing")
          }
        }

      def unstashing(processed: Vector[String]): Behavior[Command] =
        Behaviors.receive { (ctx, cmd) ⇒
          cmd match {
            case Unstashed(msg: Msg) ⇒
              ctx.log.debug(s"unstashed $msg")
              unstashing(processed :+ msg.s)
            case Unstashed(GetProcessed(replyTo)) ⇒
              ctx.log.debug(s"unstashed GetProcessed")
              replyTo ! processed
              Behaviors.same
            case msg: Msg ⇒
              ctx.log.debug(s"got $msg in unstashing")
              buffer.stash(msg)
              Behaviors.same
            case g: GetProcessed ⇒
              ctx.log.debug(s"got GetProcessed in unstashing")
              buffer.stash(g)
              Behaviors.same
            case Stash ⇒
              stashing(processed)
            case Unstash ⇒
              if (buffer.isEmpty) {
                ctx.log.debug(s"unstashing done")
                active(processed)
              } else {
                ctx.self ! Unstash // continue unstashing until buffer is empty
                val numberOfMessages = 2
                ctx.log.debug(s"Unstash $numberOfMessages of ${buffer.size}, starting with ${buffer.head}")
                buffer.unstash(ctx, unstashing(processed), numberOfMessages, Unstashed)
              }
            case GetStashSize(replyTo) ⇒
              replyTo ! buffer.size
              Behaviors.same
            case UnstashAll ⇒
              Behaviors.unhandled
            case u: Unstashed ⇒
              throw new IllegalStateException(s"Unexpected $u in unstashing")
          }
        }

      active(Vector.empty)
    }

  class MutableStash(ctx: ActorContext[Command]) extends MutableBehavior[Command] {

    private val buffer = StashBuffer.apply[Command](capacity = 10)
    private var stashing = false
    private var processed = Vector.empty[String]

    override def onMessage(cmd: Command): Behavior[Command] = {
      cmd match {
        case msg: Msg ⇒
          if (stashing)
            buffer.stash(msg)
          else
            processed :+= msg.s
          this
        case g @ GetProcessed(replyTo) ⇒
          if (stashing)
            buffer.stash(g)
          else
            replyTo ! processed
          this
        case GetStashSize(replyTo) ⇒
          replyTo ! buffer.size
          this
        case Stash ⇒
          stashing = true
          this
        case UnstashAll ⇒
          stashing = false
          buffer.unstashAll(ctx, this)
        case Unstash ⇒
          if (buffer.isEmpty) {
            stashing = false
            this
          } else {
            ctx.self ! Unstash // continue unstashing until buffer is empty
            val numberOfMessages = 2
            ctx.log.debug(s"Unstash $numberOfMessages of ${buffer.size}, starting with ${buffer.head}")
            buffer.unstash(ctx, this, numberOfMessages, Unstashed)
          }
        case Unstashed(msg: Msg) ⇒
          ctx.log.debug(s"unstashed $msg")
          processed :+= msg.s
          this
        case Unstashed(GetProcessed(replyTo)) ⇒
          ctx.log.debug(s"unstashed GetProcessed")
          replyTo ! processed
          Behaviors.same
        case _: Unstashed ⇒
          Behaviors.unhandled
      }
    }

  }

}

class ImmutableStashSpec extends StashSpec {
  import StashSpec._
  def testQualifier: String = "immutable behavior"
  def behaviorUnderTest: Behavior[Command] = immutableStash
}

class MutableStashSpec extends StashSpec {
  import StashSpec._
  def testQualifier: String = "mutable behavior"
  def behaviorUnderTest: Behavior[Command] = Behaviors.setup(ctx ⇒ new MutableStash(ctx))
}

abstract class StashSpec extends ActorTestKit with TypedAkkaSpecWithShutdown {
  import StashSpec._

  def testQualifier: String
  def behaviorUnderTest: Behavior[Command]

  s"Stashing with $testQualifier" must {

    "support unstash all" in {
      val actor = spawn(behaviorUnderTest)
      val probe = TestProbe[Vector[String]]("probe")
      val sizeProbe = TestProbe[Int]("sizeProbe")

      actor ! Msg("a")
      actor ! Msg("b")
      actor ! Msg("c")

      actor ! Stash
      actor ! Msg("d")
      actor ! Msg("e")
      actor ! Msg("f")
      actor ! GetStashSize(sizeProbe.ref)
      sizeProbe.expectMessage(3)

      actor ! UnstashAll
      actor ! GetProcessed(probe.ref)
      probe.expectMessage(Vector("a", "b", "c", "d", "e", "f"))
    }

    "support unstash a few at a time" in {
      val actor = spawn(behaviorUnderTest)
      val probe = TestProbe[Vector[String]]("probe")
      val sizeProbe = TestProbe[Int]("sizeProbe")

      actor ! Msg("a")
      actor ! Msg("b")
      actor ! Msg("c")

      actor ! Stash
      actor ! Msg("d")
      actor ! Msg("e")
      actor ! Msg("f")
      actor ! GetStashSize(sizeProbe.ref)
      sizeProbe.expectMessage(3)

      actor ! Unstash
      actor ! Msg("g") // might arrive in the middle of the unstashing
      actor ! GetProcessed(probe.ref) // this is also stashed until all unstashed
      probe.expectMessage(Vector("a", "b", "c", "d", "e", "f", "g"))
    }

  }

}
