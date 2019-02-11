/*
 * Copyright (C) 2018-2019 Lightbend Inc. <https://www.lightbend.com>
 */

package akka.actor.typed
package scaladsl

import akka.actor.testkit.typed.TestException
import akka.actor.testkit.typed.scaladsl.ScalaTestWithActorTestKit
import akka.actor.testkit.typed.scaladsl.TestProbe
import org.scalatest.WordSpecLike

object AbstractStashSpec {
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
        Behaviors.receive { (_, cmd) ⇒
          cmd match {
            case message: Msg ⇒
              active(processed :+ message.s)
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
        Behaviors.receive { (context, cmd) ⇒
          cmd match {
            case message: Msg ⇒
              buffer.stash(message)
              Behaviors.same
            case g: GetProcessed ⇒
              buffer.stash(g)
              Behaviors.same
            case GetStashSize(replyTo) ⇒
              replyTo ! buffer.size
              Behaviors.same
            case UnstashAll ⇒
              buffer.unstashAll(context, active(processed))
            case Unstash ⇒
              context.log.debug(s"Unstash ${buffer.size}")
              if (buffer.isEmpty)
                active(processed)
              else {
                context.self ! Unstash // continue unstashing until buffer is empty
                val numberOfMessages = 2
                context.log.debug(s"Unstash $numberOfMessages of ${buffer.size}, starting with ${buffer.head}")
                buffer.unstash(context, unstashing(processed), numberOfMessages, Unstashed)
              }
            case Stash ⇒
              Behaviors.unhandled
            case u: Unstashed ⇒
              throw new IllegalStateException(s"Unexpected $u in stashing")
          }
        }

      def unstashing(processed: Vector[String]): Behavior[Command] =
        Behaviors.receive { (context, cmd) ⇒
          cmd match {
            case Unstashed(message: Msg) ⇒
              context.log.debug(s"unstashed $message")
              unstashing(processed :+ message.s)
            case Unstashed(GetProcessed(replyTo)) ⇒
              context.log.debug(s"unstashed GetProcessed")
              replyTo ! processed
              Behaviors.same
            case message: Msg ⇒
              context.log.debug(s"got $message in unstashing")
              buffer.stash(message)
              Behaviors.same
            case g: GetProcessed ⇒
              context.log.debug(s"got GetProcessed in unstashing")
              buffer.stash(g)
              Behaviors.same
            case Stash ⇒
              stashing(processed)
            case Unstash ⇒
              if (buffer.isEmpty) {
                context.log.debug(s"unstashing done")
                active(processed)
              } else {
                context.self ! Unstash // continue unstashing until buffer is empty
                val numberOfMessages = 2
                context.log.debug(s"Unstash $numberOfMessages of ${buffer.size}, starting with ${buffer.head}")
                buffer.unstash(context, unstashing(processed), numberOfMessages, Unstashed)
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

  class MutableStash(context: ActorContext[Command]) extends AbstractBehavior[Command] {

    private val buffer = StashBuffer.apply[Command](capacity = 10)
    private var stashing = false
    private var processed = Vector.empty[String]

    override def onMessage(cmd: Command): Behavior[Command] = {
      cmd match {
        case message: Msg ⇒
          if (stashing)
            buffer.stash(message)
          else
            processed :+= message.s
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
          buffer.unstashAll(context, this)
        case Unstash ⇒
          if (buffer.isEmpty) {
            stashing = false
            this
          } else {
            context.self ! Unstash // continue unstashing until buffer is empty
            val numberOfMessages = 2
            context.log.debug(s"Unstash $numberOfMessages of ${buffer.size}, starting with ${buffer.head}")
            buffer.unstash(context, this, numberOfMessages, Unstashed)
          }
        case Unstashed(message: Msg) ⇒
          context.log.debug(s"unstashed $message")
          processed :+= message.s
          this
        case Unstashed(GetProcessed(replyTo)) ⇒
          context.log.debug(s"unstashed GetProcessed")
          replyTo ! processed
          Behaviors.same
        case _: Unstashed ⇒
          Behaviors.unhandled
      }
    }

  }

}

class ImmutableStashSpec extends AbstractStashSpec {
  import AbstractStashSpec._
  def testQualifier: String = "immutable behavior"
  def behaviorUnderTest: Behavior[Command] = immutableStash
}

class MutableStashSpec extends AbstractStashSpec {
  import AbstractStashSpec._
  def testQualifier: String = "mutable behavior"
  def behaviorUnderTest: Behavior[Command] = Behaviors.setup(context ⇒ new MutableStash(context))
}

abstract class AbstractStashSpec extends ScalaTestWithActorTestKit with WordSpecLike {
  import AbstractStashSpec._

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

class UnstashingSpec extends ScalaTestWithActorTestKit with WordSpecLike {

  def stashingBehavior(probe: ActorRef[String]) =
    Behaviors.setup[String] { ctx ⇒
      val stash = StashBuffer[String](10)
      def unstashing(n: Int): Behavior[String] =
        Behaviors.receiveMessage[String] {
          case "stash" ⇒
            probe.ref ! s"unstashing-$n"
            unstashing(n + 1)
          case "stash-fail" ⇒
            probe.ref ! s"stash-fail-$n"
            throw new TestException("unstash-fail")
          case "get-current" ⇒
            probe.ref ! s"current-$n"
            Behaviors.same
        }.receiveSignal {
          case (_, PreRestart) ⇒
            probe.ref ! s"pre-restart-$n"
            Behaviors.same
          case (_, PostStop) ⇒
            probe.ref ! s"post-stop-$n"
            Behaviors.same
        }

      Behaviors.receiveMessage[String] {
        case msg if msg.startsWith("stash") ⇒
          stash.stash(msg)
          Behavior.same
        case "unstash" ⇒
          stash.unstashAll(ctx, unstashing(0))
      }
    }

  "Unstashing" must {

    "work with initial Behaviors.same" in {
      val probe = TestProbe[String]()
      // unstashing is inside setup
      val ref = spawn(Behaviors.receive[String] {
        case (ctx, "unstash") ⇒
          val stash = StashBuffer[String](10)
          stash.stash("one")
          stash.unstashAll(ctx, Behavior.same)

        case (ctx, msg) ⇒
          probe.ref ! msg
          Behaviors.same
      })

      ref ! "unstash"
      probe.expectMessage("one")
    }

    "work with intermediate Behaviors.same" in {
      val probe = TestProbe[String]()
      // unstashing is inside setup
      val ref = spawn(Behaviors.receive[String] {
        case (ctx, "unstash") ⇒
          val stash = StashBuffer[String](10)
          stash.stash("one")
          stash.stash("two")
          stash.unstashAll(ctx, Behaviors.receiveMessage {
            case msg ⇒
              probe.ref ! msg
              Behaviors.same
          })
      })

      ref ! "unstash"
      probe.expectMessage("one")
      probe.expectMessage("two")
      ref ! "three"
      probe.expectMessage("three")
    }

    "work with supervised initial Behaviors.same" in {
      val probe = TestProbe[String]()
      // unstashing is inside setup
      val ref = spawn(Behaviors.supervise(Behaviors.receive[String] {
        case (ctx, "unstash") ⇒
          val stash = StashBuffer[String](10)
          stash.stash("one")
          stash.unstashAll(ctx, Behavior.same)

        case (_, msg) ⇒
          probe.ref ! msg
          Behaviors.same
      }).onFailure[TestException](SupervisorStrategy.stop))

      ref ! "unstash"
      probe.expectMessage("one")
      ref ! "two"
      probe.expectMessage("two")
    }

    "work with supervised intermediate Behaviors.same" in {
      val probe = TestProbe[String]()
      // unstashing is inside setup
      val ref = spawn(Behaviors.supervise(Behaviors.receive[String] {
        case (ctx, "unstash") ⇒
          val stash = StashBuffer[String](10)
          stash.stash("one")
          stash.stash("two")
          stash.unstashAll(ctx, Behaviors.receiveMessage {
            case msg ⇒
              probe.ref ! msg
              Behaviors.same
          })
      }).onFailure[TestException](SupervisorStrategy.stop))

      ref ! "unstash"
      probe.expectMessage("one")
      probe.expectMessage("two")
      ref ! "three"
      probe.expectMessage("three")
    }

    "signal PostStop to the latest unstashed behavior on failure" in {
      val probe = TestProbe[Any]()
      val ref =
        spawn(stashingBehavior(probe.ref))

      ref ! "stash"
      ref ! "stash-fail"
      ref ! "unstash"
      probe.expectMessage("unstashing-0")
      probe.expectMessage("stash-fail-1")
      probe.expectMessage("post-stop-1")
    }

    "signal PostStop to the latest unstashed behavior on failure with supervision" in {
      val probe = TestProbe[Any]()
      val ref =
        spawn(Behaviors.supervise(stashingBehavior(probe.ref))
          .onFailure[TestException](SupervisorStrategy.stop))

      ref ! "stash"
      ref ! "stash-fail"
      ref ! "unstash"
      probe.expectMessage("unstashing-0")
      probe.expectMessage("stash-fail-1")
      probe.expectMessage("post-stop-1")
    }

    "signal PreRestart to the latest unstashed behavior on failure" in {
      val probe = TestProbe[Any]()
      val ref =
        spawn(Behaviors.supervise(stashingBehavior(probe.ref))
          .onFailure[TestException](SupervisorStrategy.restart))

      ref ! "stash"
      ref ! "stash-fail"
      ref ! "unstash"
      probe.expectMessage("unstashing-0")
      probe.expectMessage("stash-fail-1")
      probe.expectMessage("pre-restart-1")
    }

    "handle resume correctly on failure unstashing" in {
      val probe = TestProbe[Any]()
      val ref =
        spawn(Behaviors.supervise(stashingBehavior(probe.ref))
          .onFailure[TestException](SupervisorStrategy.resume))

      ref ! "stash"
      ref ! "stash-fail"
      ref ! "unstash"
      ref ! "get-current"

      probe.expectMessage("unstashing-0")
      probe.expectMessage("stash-fail-1")
      probe.expectMessage("current-1")
    }

    "be possible in combination with setup" in {
      val probe = TestProbe[String]()
      val ref = spawn(Behaviors.setup[String] { _ ⇒
        val stash = StashBuffer[String](10)
        stash.stash("one")

        // unstashing is inside setup
        Behaviors.receiveMessage {
          case "unstash" ⇒
            Behaviors.setup { ctx ⇒
              stash.unstashAll(ctx, Behavior.same)
            }
          case msg ⇒
            probe.ref ! msg
            Behavior.same
        }
      })

      ref ! "unstash"
      probe.expectMessage("one")
    }

  }
}
