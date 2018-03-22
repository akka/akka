/**
 * Copyright (C) 2017-2018 Lightbend Inc. <https://www.lightbend.com>
 */

package akka.actor.typed

import akka.actor.typed.scaladsl.Behaviors
import akka.testkit.typed.TestKitSettings
import akka.testkit.typed.scaladsl._

import scala.util.control.NoStackTrace

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

class DeferredSpec extends ActorTestKit with TypedAkkaSpecWithShutdown {

  import DeferredSpec._
  implicit val testSettings = TestKitSettings(system)

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
      val behv = Behaviors.setup[Command] { ctx ⇒
        val child = ctx.spawnAnonymous(Behaviors.setup[Command] { _ ⇒
          probe.ref ! Started
          throw new RuntimeException("simulated exc from factory") with NoStackTrace
        })
        ctx.watch(child)
        Behaviors.receive[Command]((_, _) ⇒ Behaviors.same).receiveSignal {
          case (_, Terminated(`child`)) ⇒
            probe.ref ! Pong
            Behaviors.stopped
        }
      }
      spawn(behv)
      probe.expectMessage(Started)
      probe.expectMessage(Pong)
    }

    "must stop when deferred result it Stopped" in {
      val probe = TestProbe[Event]("evt")
      val behv = Behaviors.setup[Command] { ctx ⇒
        val child = ctx.spawnAnonymous(Behaviors.setup[Command](_ ⇒ Behaviors.stopped))
        ctx.watch(child)
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
  }
}

class DeferredStubbedSpec extends TypedAkkaSpec {

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

