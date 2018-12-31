/*
 * Copyright (C) 2017-2019 Lightbend Inc. <https://www.lightbend.com>
 */

package akka.actor.typed

import akka.testkit.EventFilter
import akka.actor.typed.scaladsl.Behaviors
import akka.actor.testkit.typed.TestKitSettings
import akka.actor.testkit.typed.scaladsl._

import scala.util.control.NoStackTrace
import akka.actor.ActorInitializationException
import org.scalatest.{ Matchers, WordSpec, WordSpecLike }

object DeferredSpec {
  sealed trait Command
  case object Ping extends Command

  sealed trait Event
  case object Pong extends Event
  case object Started extends Event

  def target(monitor: ActorRef[Event]): Behavior[Command] =
    Behaviors.receive((_, cmd) ⇒ cmd match {
      case Ping ⇒
        monitor ! Pong
        Behaviors.same
    })
}

class DeferredSpec extends ScalaTestWithActorTestKit(
  """
    akka.loggers = [akka.testkit.TestEventListener]
    """) with WordSpecLike {

  import DeferredSpec._
  implicit val testSettings = TestKitSettings(system)

  // FIXME eventfilter support in typed testkit
  import scaladsl.adapter._
  implicit val untypedSystem = system.toUntyped

  "Deferred behavior" must {
    "must create underlying" in {
      val probe = TestProbe[Event]("evt")
      val behv = Behaviors.setup[Command] { _ ⇒
        probe.ref ! Started
        target(probe.ref)
      }
      probe.expectNoMessage() // not yet
      spawn(behv)
      // it's supposed to be created immediately (not waiting for first message)
      probe.expectMessage(Started)
    }

    "must stop when exception from factory" in {
      val probe = TestProbe[Event]("evt")
      val behv = Behaviors.setup[Command] { context ⇒
        val child = context.spawnAnonymous(Behaviors.setup[Command] { _ ⇒
          probe.ref ! Started
          throw new RuntimeException("simulated exc from factory") with NoStackTrace
        })
        context.watch(child)
        Behaviors.receive[Command]((_, _) ⇒ Behaviors.same).receiveSignal {
          case (_, Terminated(`child`)) ⇒
            probe.ref ! Pong
            Behaviors.stopped
        }
      }
      EventFilter[ActorInitializationException](occurrences = 1).intercept {
        spawn(behv)
        probe.expectMessage(Started)
        probe.expectMessage(Pong)
      }
    }

    "must stop when deferred result it Stopped" in {
      val probe = TestProbe[Event]("evt")
      val behv = Behaviors.setup[Command] { context ⇒
        val child = context.spawnAnonymous(Behaviors.setup[Command](_ ⇒ Behaviors.stopped))
        context.watch(child)
        Behaviors.receive[Command]((_, _) ⇒ Behaviors.same).receiveSignal {
          case (_, Terminated(`child`)) ⇒
            probe.ref ! Pong
            Behaviors.stopped
        }
      }
      spawn(behv)
      probe.expectMessage(Pong)
    }

    "must create underlying when nested" in {
      val probe = TestProbe[Event]("evt")
      val behv = Behaviors.setup[Command] { _ ⇒
        Behaviors.setup[Command] { _ ⇒
          probe.ref ! Started
          target(probe.ref)
        }
      }
      spawn(behv)
      probe.expectMessage(Started)
    }

    "must un-defer underlying when wrapped by widen" in {
      val probe = TestProbe[Event]("evt")
      val behv = Behaviors.setup[Command] { _ ⇒
        probe.ref ! Started
        target(probe.ref)
      }.widen[Command] {
        case m ⇒ m
      }
      probe.expectNoMessage() // not yet
      val ref = spawn(behv)
      // it's supposed to be created immediately (not waiting for first message)
      probe.expectMessage(Started)
      ref ! Ping
      probe.expectMessage(Pong)
    }

    "must un-defer underlying when wrapped by monitor" in {
      // monitor is implemented with tap, so this is testing both
      val probe = TestProbe[Event]("evt")
      val monitorProbe = TestProbe[Command]("monitor")
      val behv = Behaviors.monitor(monitorProbe.ref, Behaviors.setup[Command] { _ ⇒
        probe.ref ! Started
        target(probe.ref)
      })
      probe.expectNoMessage() // not yet
      val ref = spawn(behv)
      // it's supposed to be created immediately (not waiting for first message)
      probe.expectMessage(Started)
      ref ! Ping
      monitorProbe.expectMessage(Ping)
      probe.expectMessage(Pong)
    }

    "must not allow setup(same)" in {
      val probe = TestProbe[Any]()
      val behv = Behaviors.setup[Command] { _ ⇒
        Behaviors.setup[Command] { _ ⇒ Behaviors.same }
      }
      EventFilter[ActorInitializationException](occurrences = 1).intercept {
        val ref = spawn(behv)
        probe.expectTerminated(ref, probe.remainingOrDefault)
      }
    }
  }
}

class DeferredStubbedSpec extends WordSpec with Matchers {

  import DeferredSpec._

  "must create underlying deferred behavior immediately" in {
    val inbox = TestInbox[Event]("evt")
    val behv = Behaviors.setup[Command] { _ ⇒
      inbox.ref ! Started
      target(inbox.ref)
    }
    BehaviorTestKit(behv)
    // it's supposed to be created immediately (not waiting for first message)
    inbox.receiveMessage() should ===(Started)
  }

  "must stop when exception from factory" in {
    val inbox = TestInbox[Event]("evt")
    val exc = new RuntimeException("simulated exc from factory") with NoStackTrace
    val behv = Behaviors.setup[Command] { _ ⇒
      inbox.ref ! Started
      throw exc
    }
    intercept[RuntimeException] {
      BehaviorTestKit(behv)
    } should ===(exc)
    inbox.receiveMessage() should ===(Started)
  }

}

