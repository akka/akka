/*
 * Copyright (C) 2018-2019 Lightbend Inc. <https://www.lightbend.com>
 */

package akka.actor.typed
package scaladsl

import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

import akka.actor.DeadLetter
import scala.concurrent.duration._
import akka.actor.testkit.typed.TestException
import akka.actor.testkit.typed.scaladsl.ScalaTestWithActorTestKit
import akka.actor.testkit.typed.scaladsl.TestProbe
import akka.testkit.EventFilter
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
    Behaviors.setup[Command] { _ =>
      val buffer = StashBuffer[Command](capacity = 10)

      def active(processed: Vector[String]): Behavior[Command] =
        Behaviors.receive { (_, cmd) =>
          cmd match {
            case message: Msg =>
              active(processed :+ message.s)
            case GetProcessed(replyTo) =>
              replyTo ! processed
              Behaviors.same
            case Stash =>
              stashing(processed)
            case GetStashSize(replyTo) =>
              replyTo ! 0
              Behaviors.same
            case UnstashAll =>
              Behaviors.unhandled
            case Unstash =>
              Behaviors.unhandled
            case u: Unstashed =>
              throw new IllegalStateException(s"Unexpected $u in active")
          }
        }

      def stashing(processed: Vector[String]): Behavior[Command] =
        Behaviors.receive { (context, cmd) =>
          cmd match {
            case message: Msg =>
              buffer.stash(message)
              Behaviors.same
            case g: GetProcessed =>
              buffer.stash(g)
              Behaviors.same
            case GetStashSize(replyTo) =>
              replyTo ! buffer.size
              Behaviors.same
            case UnstashAll =>
              buffer.unstashAll(context, active(processed))
            case Unstash =>
              context.log.debug(s"Unstash ${buffer.size}")
              if (buffer.isEmpty)
                active(processed)
              else {
                context.self ! Unstash // continue unstashing until buffer is empty
                val numberOfMessages = 2
                context.log.debug(s"Unstash $numberOfMessages of ${buffer.size}, starting with ${buffer.head}")
                buffer.unstash(context, unstashing(processed), numberOfMessages, Unstashed)
              }
            case Stash =>
              Behaviors.unhandled
            case u: Unstashed =>
              throw new IllegalStateException(s"Unexpected $u in stashing")
          }
        }

      def unstashing(processed: Vector[String]): Behavior[Command] =
        Behaviors.receive { (context, cmd) =>
          cmd match {
            case Unstashed(message: Msg) =>
              context.log.debug(s"unstashed $message")
              unstashing(processed :+ message.s)
            case Unstashed(GetProcessed(replyTo)) =>
              context.log.debug(s"unstashed GetProcessed")
              replyTo ! processed
              Behaviors.same
            case message: Msg =>
              context.log.debug(s"got $message in unstashing")
              buffer.stash(message)
              Behaviors.same
            case g: GetProcessed =>
              context.log.debug(s"got GetProcessed in unstashing")
              buffer.stash(g)
              Behaviors.same
            case Stash =>
              stashing(processed)
            case Unstash =>
              if (buffer.isEmpty) {
                context.log.debug(s"unstashing done")
                active(processed)
              } else {
                context.self ! Unstash // continue unstashing until buffer is empty
                val numberOfMessages = 2
                context.log.debug(s"Unstash $numberOfMessages of ${buffer.size}, starting with ${buffer.head}")
                buffer.unstash(context, unstashing(processed), numberOfMessages, Unstashed)
              }
            case GetStashSize(replyTo) =>
              replyTo ! buffer.size
              Behaviors.same
            case UnstashAll =>
              Behaviors.unhandled
            case u: Unstashed =>
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
        case message: Msg =>
          if (stashing)
            buffer.stash(message)
          else
            processed :+= message.s
          this
        case g @ GetProcessed(replyTo) =>
          if (stashing)
            buffer.stash(g)
          else
            replyTo ! processed
          this
        case GetStashSize(replyTo) =>
          replyTo ! buffer.size
          this
        case Stash =>
          stashing = true
          this
        case UnstashAll =>
          stashing = false
          buffer.unstashAll(context, this)
        case Unstash =>
          if (buffer.isEmpty) {
            stashing = false
            this
          } else {
            context.self ! Unstash // continue unstashing until buffer is empty
            val numberOfMessages = 2
            context.log.debug(s"Unstash $numberOfMessages of ${buffer.size}, starting with ${buffer.head}")
            buffer.unstash(context, this, numberOfMessages, Unstashed)
          }
        case Unstashed(message: Msg) =>
          context.log.debug(s"unstashed $message")
          processed :+= message.s
          this
        case Unstashed(GetProcessed(replyTo)) =>
          context.log.debug(s"unstashed GetProcessed")
          replyTo ! processed
          Behaviors.same
        case _: Unstashed =>
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
  def behaviorUnderTest: Behavior[Command] = Behaviors.setup(context => new MutableStash(context))
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

class UnstashingSpec extends ScalaTestWithActorTestKit("""
  akka.loggers = ["akka.testkit.TestEventListener"]
  """) with WordSpecLike {

  // needed for EventFilter
  private implicit val untypedSys: akka.actor.ActorSystem = {
    import akka.actor.typed.scaladsl.adapter._
    system.toUntyped
  }

  private def slowStoppingChild(latch: CountDownLatch): Behavior[String] =
    Behaviors.receiveSignal {
      case (_, PostStop) =>
        latch.await(10, TimeUnit.SECONDS)
        Behaviors.same
    }

  private def stashingBehavior(probe: ActorRef[String], withSlowStoppingChild: Option[CountDownLatch] = None) = {
    Behaviors.setup[String] { ctx =>
      withSlowStoppingChild.foreach(latch => ctx.spawnAnonymous(slowStoppingChild(latch)))

      val stash = StashBuffer[String](10)

      def unstashing(n: Int): Behavior[String] =
        Behaviors
          .receiveMessage[String] {
            case "stash" =>
              probe.ref ! s"unstashing-$n"
              unstashing(n + 1)
            case "stash-fail" =>
              probe.ref ! s"stash-fail-$n"
              throw TestException("unstash-fail")
            case "get-current" =>
              probe.ref ! s"current-$n"
              Behaviors.same
            case "get-stash-size" =>
              probe.ref ! s"stash-size-${stash.size}"
              Behaviors.same
            case "unstash" =>
              // when testing resume
              stash.unstashAll(ctx, unstashing(n))
          }
          .receiveSignal {
            case (_, PreRestart) =>
              probe.ref ! s"pre-restart-$n"
              Behaviors.same
            case (_, PostStop) =>
              probe.ref ! s"post-stop-$n"
              Behaviors.same
          }

      Behaviors.receiveMessage[String] {
        case msg if msg.startsWith("stash") =>
          stash.stash(msg)
          Behaviors.same
        case "unstash" =>
          stash.unstashAll(ctx, unstashing(0))
        case "get-current" =>
          probe.ref ! s"current-00"
          Behaviors.same
        case "get-stash-size" =>
          probe.ref ! s"stash-size-${stash.size}"
          Behaviors.same
      }
    }
  }

  "Unstashing" must {

    "work with initial Behaviors.same" in {
      // FIXME #26148 unstashAll doesn't support Behavior.same
      pending

      val probe = TestProbe[String]()
      // unstashing is inside setup
      val ref = spawn(Behaviors.receive[String] {
        case (ctx, "unstash") =>
          val stash = StashBuffer[String](10)
          stash.stash("one")
          stash.unstashAll(ctx, Behaviors.same)

        case (_, msg) =>
          probe.ref ! msg
          Behaviors.same
      })

      ref ! "unstash"
      probe.expectMessage("one")
    }

    "work with intermediate Behaviors.same" in {
      val probe = TestProbe[String]()
      // unstashing is inside setup
      val ref = spawn(Behaviors.receivePartial[String] {
        case (ctx, "unstash") =>
          val stash = StashBuffer[String](10)
          stash.stash("one")
          stash.stash("two")
          stash.unstashAll(ctx, Behaviors.receiveMessage { msg =>
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
      // FIXME #26148 unstashAll doesn't support Behavior.same
      pending

      val probe = TestProbe[String]()
      // unstashing is inside setup
      val ref = spawn(
        Behaviors
          .supervise(Behaviors.receivePartial[String] {
            case (ctx, "unstash") =>
              val stash = StashBuffer[String](10)
              stash.stash("one")
              stash.unstashAll(ctx, Behaviors.same)

            case (_, msg) =>
              probe.ref ! msg
              Behaviors.same
          })
          .onFailure[TestException](SupervisorStrategy.stop))

      ref ! "unstash"
      probe.expectMessage("one")
      ref ! "two"
      probe.expectMessage("two")
    }

    "work with supervised intermediate Behaviors.same" in {
      val probe = TestProbe[String]()
      // unstashing is inside setup
      val ref = spawn(
        Behaviors
          .supervise(Behaviors.receivePartial[String] {
            case (ctx, "unstash") =>
              val stash = StashBuffer[String](10)
              stash.stash("one")
              stash.stash("two")
              stash.unstashAll(ctx, Behaviors.receiveMessage { msg =>
                probe.ref ! msg
                Behaviors.same
              })
          })
          .onFailure[TestException](SupervisorStrategy.stop))

      ref ! "unstash"
      probe.expectMessage("one")
      probe.expectMessage("two")
      ref ! "three"
      probe.expectMessage("three")
    }

    def testPostStop(probe: TestProbe[String], ref: ActorRef[String]): Unit = {
      ref ! "stash"
      ref ! "stash"
      ref ! "stash-fail"
      ref ! "stash"
      EventFilter[TestException](start = "unstash-fail", occurrences = 1).intercept {
        ref ! "unstash"
        probe.expectMessage("unstashing-0")
        probe.expectMessage("unstashing-1")
        probe.expectMessage("stash-fail-2")
        probe.expectMessage("post-stop-2")
      }
    }

    "signal PostStop to the latest unstashed behavior on failure" in {
      val probe = TestProbe[String]()
      val ref = spawn(stashingBehavior(probe.ref))
      testPostStop(probe, ref)
    }

    "signal PostStop to the latest unstashed behavior on failure with stop supervision" in {
      val probe = TestProbe[String]()
      val ref =
        spawn(Behaviors.supervise(stashingBehavior(probe.ref)).onFailure[TestException](SupervisorStrategy.stop))
      testPostStop(probe, ref)
    }

    def testPreRestart(probe: TestProbe[String], childLatch: Option[CountDownLatch], ref: ActorRef[String]): Unit = {
      ref ! "stash"
      ref ! "stash"
      ref ! "stash-fail"
      ref ! "stash"
      EventFilter[TestException](start = "Supervisor RestartSupervisor saw failure: unstash-fail", occurrences = 1)
        .intercept {
          ref ! "unstash"
          // when childLatch is defined this be stashed in the internal stash of the RestartSupervisor
          // because it's waiting for child to stop
          ref ! "get-current"

          probe.expectMessage("unstashing-0")
          probe.expectMessage("unstashing-1")
          probe.expectMessage("stash-fail-2")
          probe.expectMessage("pre-restart-2")

          childLatch.foreach(_.countDown())
          probe.expectMessage("current-00")

          ref ! "get-stash-size"
          probe.expectMessage("stash-size-0")
        }
    }

    "signal PreRestart to the latest unstashed behavior on failure with restart supervision" in {
      val probe = TestProbe[String]()
      val ref =
        spawn(Behaviors.supervise(stashingBehavior(probe.ref)).onFailure[TestException](SupervisorStrategy.restart))

      testPreRestart(probe, None, ref)
      // one more time to ensure that the restart strategy is kept
      testPreRestart(probe, None, ref)
    }

    "signal PreRestart to the latest unstashed behavior on failure with restart supervision and slow stopping child" in {
      val probe = TestProbe[String]()
      val childLatch = new CountDownLatch(1)
      val ref =
        spawn(
          Behaviors
            .supervise(stashingBehavior(probe.ref, Some(childLatch)))
            .onFailure[TestException](SupervisorStrategy.restart))

      testPreRestart(probe, Some(childLatch), ref)
    }

    "signal PreRestart to the latest unstashed behavior on failure with backoff supervision" in {
      val probe = TestProbe[String]()
      val ref =
        spawn(
          Behaviors
            .supervise(stashingBehavior(probe.ref))
            .onFailure[TestException](SupervisorStrategy.restartWithBackoff(100.millis, 100.millis, 0.0)))

      testPreRestart(probe, None, ref)

      // one more time to ensure that the backoff strategy is kept
      testPreRestart(probe, None, ref)
    }

    "signal PreRestart to the latest unstashed behavior on failure with backoff supervision and slow stopping child" in {
      val probe = TestProbe[String]()
      val childLatch = new CountDownLatch(1)
      val ref =
        spawn(
          Behaviors
            .supervise(stashingBehavior(probe.ref, Some(childLatch)))
            .onFailure[TestException](SupervisorStrategy.restartWithBackoff(100.millis, 100.millis, 0.0)))

      testPreRestart(probe, Some(childLatch), ref)
    }

    "handle resume correctly on failure unstashing" in {
      val probe = TestProbe[String]()
      val ref =
        spawn(Behaviors.supervise(stashingBehavior(probe.ref)).onFailure[TestException](SupervisorStrategy.resume))

      ref ! "stash"
      ref ! "stash"
      ref ! "stash-fail"
      ref ! "stash"
      ref ! "stash"
      ref ! "stash"
      ref ! "stash-fail"
      ref ! "stash"
      EventFilter[TestException](start = "Supervisor ResumeSupervisor saw failure: unstash-fail", occurrences = 1)
        .intercept {
          ref ! "unstash"
          ref ! "get-current"

          probe.expectMessage("unstashing-0")
          probe.expectMessage("unstashing-1")
          probe.expectMessage("stash-fail-2")
          probe.expectMessage("current-2")
          ref ! "get-stash-size"
          probe.expectMessage("stash-size-5")
        }

      ref ! "unstash"
      ref ! "get-current"
      probe.expectMessage("unstashing-2")
      probe.expectMessage("unstashing-3")
      probe.expectMessage("unstashing-4")
      probe.expectMessage("stash-fail-5")
      probe.expectMessage("current-5")
      ref ! "get-stash-size"
      probe.expectMessage("stash-size-1")

      ref ! "unstash"
      ref ! "get-current"
      probe.expectMessage("unstashing-5")
      probe.expectMessage("current-6")

      ref ! "get-stash-size"
      probe.expectMessage("stash-size-0")
    }

    "be possible in combination with setup" in {
      val probe = TestProbe[String]()
      val ref = spawn(Behaviors.setup[String] { _ =>
        val stash = StashBuffer[String](10)
        stash.stash("one")

        // unstashing is inside setup
        Behaviors.receiveMessage {
          case "unstash" =>
            Behaviors.setup[String] { ctx =>
              stash.unstashAll(ctx, Behaviors.same)
            }
          case msg =>
            probe.ref ! msg
            Behaviors.same
        }
      })

      ref ! "unstash"
      probe.expectMessage("one")
    }

    "deal with unhandled the same way as normal unhandled" in {
      val probe = TestProbe[String]()
      val ref = spawn(Behaviors.setup[String] { ctx =>
        val stash = StashBuffer[String](10)
        stash.stash("unhandled")
        stash.stash("handled")
        stash.stash("handled")
        stash.stash("unhandled")
        stash.stash("handled")

        def unstashing(n: Int): Behavior[String] =
          Behaviors.receiveMessage {
            case "unhandled" => Behaviors.unhandled
            case "handled" =>
              probe.ref ! s"handled $n"
              unstashing(n + 1)
          }

        Behaviors.receiveMessage {
          case "unstash" =>
            stash.unstashAll(ctx, unstashing(1))
        }
      })

      EventFilter.warning(start = "unhandled message from", occurrences = 2).intercept {
        ref ! "unstash"
      }
      probe.expectMessage("handled 1")
      probe.expectMessage("handled 2")
      probe.expectMessage("handled 3")

      ref ! "handled"
      probe.expectMessage("handled 4")
    }

    "fail quick on invalid start behavior" in {
      val stash = StashBuffer[String](10)
      stash.stash("one")
      intercept[IllegalArgumentException](stash.unstashAll(null, Behaviors.unhandled))
    }

    "deal with initial stop" in {
      val probe = TestProbe[Any]
      val ref = spawn(Behaviors.setup[String] { ctx =>
        val stash = StashBuffer[String](10)
        stash.stash("one")

        Behaviors.receiveMessage {
          case "unstash" =>
            stash.unstashAll(ctx, Behaviors.stopped)
        }
      })

      ref ! "unstash"
      probe.expectTerminated(ref)
    }

    "deal with stop" in {
      val probe = TestProbe[Any]
      import akka.actor.typed.scaladsl.adapter._
      untypedSys.eventStream.subscribe(probe.ref.toUntyped, classOf[DeadLetter])
      val ref = spawn(Behaviors.setup[String] { ctx =>
        val stash = StashBuffer[String](10)
        stash.stash("one")
        stash.stash("two")

        Behaviors.receiveMessage {
          case "unstash" =>
            stash.unstashAll(ctx, Behaviors.receiveMessage {
              case unstashed =>
                probe.ref ! unstashed
                Behaviors.stopped
            })
          case _ =>
            Behaviors.same
        }
      })
      ref ! "unstash"
      probe.expectMessage("one")
      probe.expectMessageType[DeadLetter].message should equal("two")
      probe.expectTerminated(ref)
    }

    "work with initial same" in {
      val probe = TestProbe[Any]
      val ref = spawn(Behaviors.setup[String] { ctx =>
        val stash = StashBuffer[String](10)
        stash.stash("one")
        stash.stash("two")

        Behaviors.receiveMessage {
          case "unstash" =>
            stash.unstashAll(ctx, Behaviors.same)
          case msg =>
            probe.ref ! msg
            Behaviors.same
        }
      })
      ref ! "unstash"
      probe.expectMessage("one")
      probe.expectMessage("two")
    }
  }
}
