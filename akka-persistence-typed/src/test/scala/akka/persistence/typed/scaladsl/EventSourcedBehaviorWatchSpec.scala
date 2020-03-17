/*
 * Copyright (C) 2019-2020 Lightbend Inc. <https://www.lightbend.com>
 */

package akka.persistence.typed.scaladsl

import java.util.concurrent.atomic.AtomicInteger

import akka.actor.testkit.typed.TestException
import akka.actor.testkit.typed.scaladsl.{ LogCapturing, LoggingTestKit, ScalaTestWithActorTestKit, TestProbe }
import akka.actor.typed._
import akka.actor.typed.scaladsl.{ ActorContext, Behaviors }
import akka.persistence.Recovery
import akka.persistence.typed.internal.{
  BehaviorSetup,
  EventSourcedSettings,
  InternalProtocol,
  NoOpSnapshotAdapter,
  StashState
}
import akka.persistence.typed.internal.EventSourcedBehaviorImpl.WriterIdentity
import akka.persistence.typed.{ NoOpEventAdapter, PersistenceId, RecoveryCompleted }
import akka.serialization.jackson.CborSerializable
import akka.util.ConstantFun
import org.scalatest.wordspec.AnyWordSpecLike

object EventSourcedBehaviorWatchSpec {
  sealed trait Command extends CborSerializable
  case object Fail extends Command
  case object Stop extends Command
  final case class ChildHasFailed(t: akka.actor.typed.ChildFailed)
  final case class HasTerminated(ref: ActorRef[_])
}

class EventSourcedBehaviorWatchSpec
    extends ScalaTestWithActorTestKit(EventSourcedBehaviorSpec.conf)
    with AnyWordSpecLike
    with LogCapturing {

  import EventSourcedBehaviorWatchSpec._

  private val cause = TestException("Dodge this.")

  private val pidCounter = new AtomicInteger(0)

  private def nextPid: PersistenceId = PersistenceId.ofUniqueId(s"${pidCounter.incrementAndGet()}")

  private def setup(
      pf: PartialFunction[(String, Signal), Unit],
      settings: EventSourcedSettings,
      context: ActorContext[_]): BehaviorSetup[Command, String, String] =
    new BehaviorSetup[Command, String, String](
      context.asInstanceOf[ActorContext[InternalProtocol]],
      nextPid,
      emptyState = "",
      commandHandler = (_, _) => Effect.none,
      eventHandler = (state, evt) => state + evt,
      WriterIdentity.newIdentity(),
      pf,
      _ => Set.empty[String],
      NoOpEventAdapter.instance[String],
      NoOpSnapshotAdapter.instance[String],
      snapshotWhen = ConstantFun.scalaAnyThreeToFalse,
      Recovery(),
      RetentionCriteria.disabled,
      holdingRecoveryPermit = false,
      settings = settings,
      stashState = new StashState(context.asInstanceOf[ActorContext[InternalProtocol]], settings))

  "A typed persistent parent actor watching a child" must {

    "throw a DeathPactException from parent when not handling the child Terminated signal" in {

      val parent =
        spawn(Behaviors.setup[Command] { context =>
          val child = context.spawnAnonymous(Behaviors.receive[Command] { (_, _) =>
            throw cause
          })

          context.watch(child)

          EventSourcedBehavior[Command, String, String](nextPid, emptyState = "", commandHandler = (_, cmd) => {
            child ! cmd
            Effect.none
          }, eventHandler = (state, evt) => state + evt)
        })

      LoggingTestKit.error[TestException].expect {
        LoggingTestKit.error[DeathPactException].expect {
          parent ! Fail
        }
      }
      createTestProbe().expectTerminated(parent)
    }

    "behave as expected if a user's signal handler is side effecting" in {
      val signalHandler: PartialFunction[(String, Signal), Unit] = {
        case (_, RecoveryCompleted) =>
          java.time.Instant.now.getNano
          Behaviors.same
      }

      Behaviors.setup[Command] { context =>
        val settings = EventSourcedSettings(context.system, "", "")

        setup(signalHandler, settings, context).onSignal("", RecoveryCompleted, false) shouldEqual true
        setup(PartialFunction.empty, settings, context).onSignal("", RecoveryCompleted, false) shouldEqual false

        Behaviors.empty
      }

      val parent =
        spawn(Behaviors.setup[Command] { context =>
          val child = context.spawnAnonymous(Behaviors.receive[Command] { (_, _) =>
            throw cause
          })

          context.watch(child)

          EventSourcedBehavior[Command, String, String](nextPid, emptyState = "", commandHandler = (_, cmd) => {
            child ! cmd
            Effect.none
          }, eventHandler = (state, evt) => state + evt).receiveSignal(signalHandler)
        })

      LoggingTestKit.error[TestException].expect {
        LoggingTestKit.error[DeathPactException].expect {
          parent ! Fail
        }
      }
      createTestProbe().expectTerminated(parent)
    }

    "receive a Terminated when handling the signal" in {
      val probe = TestProbe[AnyRef]()

      val parent =
        spawn(Behaviors.setup[Stop.type] { context =>
          val child = context.spawnAnonymous(Behaviors.setup[Stop.type] { c =>
            Behaviors.receive[Stop.type] { (_, _) =>
              context.stop(c.self)
              Behaviors.stopped
            }
          })

          probe.ref ! child
          context.watch(child)

          EventSourcedBehavior[Stop.type, String, String](nextPid, emptyState = "", commandHandler = (_, cmd) => {
            child ! cmd
            Effect.none
          }, eventHandler = (state, evt) => state + evt).receiveSignal {
            case (_, t: Terminated) =>
              probe.ref ! HasTerminated(t.ref)
              Behaviors.stopped
          }
        })

      val child = probe.expectMessageType[ActorRef[Stop.type]]

      parent ! Stop
      probe.expectMessageType[HasTerminated].ref shouldEqual child
    }

    "receive a ChildFailed when handling the signal" in {
      val probe = TestProbe[AnyRef]()

      val parent =
        spawn(Behaviors.setup[Fail.type] { context =>
          val child = context.spawnAnonymous(Behaviors.receive[Fail.type] { (_, _) =>
            throw cause
          })

          probe.ref ! child
          context.watch(child)

          EventSourcedBehavior[Fail.type, String, String](nextPid, emptyState = "", commandHandler = (_, cmd) => {
            child ! cmd
            Effect.none
          }, eventHandler = (state, evt) => state + evt).receiveSignal {
            case (_, t: ChildFailed) =>
              probe.ref ! ChildHasFailed(t)
              Behaviors.same
          }
        })

      val child = probe.expectMessageType[ActorRef[Fail.type]]

      LoggingTestKit.error[TestException].expect {
        parent ! Fail
      }
      val failed = probe.expectMessageType[ChildHasFailed].t
      failed.ref shouldEqual child
      failed.cause shouldEqual cause
    }

  }
}
