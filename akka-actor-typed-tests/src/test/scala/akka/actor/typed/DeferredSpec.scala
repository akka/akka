/**
 * Copyright (C) 2017-2018 Lightbend Inc. <https://www.lightbend.com>
 */
package akka.actor.typed

import scala.concurrent.duration._
import scala.util.control.NoStackTrace
import akka.actor.typed.scaladsl.Behaviors
import akka.actor.typed.scaladsl.Behaviors.BehaviorDecorators
import akka.testkit.typed.{ BehaviorTestkit, TestInbox, TestKit, TestKitSettings }
import akka.testkit.typed.scaladsl._

object DeferredSpec {
  sealed trait Command
  case object Ping extends Command

  sealed trait Event
  case object Pong extends Event
  case object Started extends Event

  def target(monitor: ActorRef[Event]): Behavior[Command] =
    Behaviors.immutable((_, cmd) ⇒ cmd match {
      case Ping ⇒
        monitor ! Pong
        Behaviors.same
    })
}

class DeferredSpec extends TestKit with TypedAkkaSpec {

  import DeferredSpec._
  implicit val testSettings = TestKitSettings(system)

  "Deferred behaviour" must {
    "must create underlying" in {
      val probe = TestProbe[Event]("evt")
      val behv = Behaviors.deferred[Command] { _ ⇒
        probe.ref ! Started
        target(probe.ref)
      }
      probe.expectNoMessage() // not yet
      spawn(behv)
      // it's supposed to be created immediately (not waiting for first message)
      probe.expectMsg(Started)
    }

    "must stop when exception from factory" in {
      val probe = TestProbe[Event]("evt")
      val behv = Behaviors.deferred[Command] { ctx ⇒
        val child = ctx.spawnAnonymous(Behaviors.deferred[Command] { _ ⇒
          probe.ref ! Started
          throw new RuntimeException("simulated exc from factory") with NoStackTrace
        })
        ctx.watch(child)
        Behaviors.immutable[Command]((_, _) ⇒ Behaviors.same).onSignal {
          case (_, Terminated(`child`)) ⇒
            probe.ref ! Pong
            Behaviors.stopped
        }
      }
      spawn(behv)
      probe.expectMsg(Started)
      probe.expectMsg(Pong)
    }

    "must stop when deferred result it Stopped" in {
      val probe = TestProbe[Event]("evt")
      val behv = Behaviors.deferred[Command] { ctx ⇒
        val child = ctx.spawnAnonymous(Behaviors.deferred[Command](_ ⇒ Behaviors.stopped))
        ctx.watch(child)
        Behaviors.immutable[Command]((_, _) ⇒ Behaviors.same).onSignal {
          case (_, Terminated(`child`)) ⇒
            probe.ref ! Pong
            Behaviors.stopped
        }
      }
      spawn(behv)
      probe.expectMsg(Pong)
    }

    "must create underlying when nested" in {
      val probe = TestProbe[Event]("evt")
      val behv = Behaviors.deferred[Command] { _ ⇒
        Behaviors.deferred[Command] { _ ⇒
          probe.ref ! Started
          target(probe.ref)
        }
      }
      spawn(behv)
      probe.expectMsg(Started)
    }

    "must un-defer underlying when wrapped by widen" in {
      val probe = TestProbe[Event]("evt")
      val behv = Behaviors.deferred[Command] { _ ⇒
        probe.ref ! Started
        target(probe.ref)
      }.widen[Command] {
        case m ⇒ m
      }
      probe.expectNoMessage() // not yet
      val ref = spawn(behv)
      // it's supposed to be created immediately (not waiting for first message)
      probe.expectMsg(Started)
      ref ! Ping
      probe.expectMsg(Pong)
    }

    "must un-defer underlying when wrapped by monitor" in {
      // monitor is implemented with tap, so this is testing both
      val probe = TestProbe[Event]("evt")
      val monitorProbe = TestProbe[Command]("monitor")
      val behv = Behaviors.monitor(monitorProbe.ref, Behaviors.deferred[Command] { _ ⇒
        probe.ref ! Started
        target(probe.ref)
      })
      probe.expectNoMessage() // not yet
      val ref = spawn(behv)
      // it's supposed to be created immediately (not waiting for first message)
      probe.expectMsg(Started)
      ref ! Ping
      monitorProbe.expectMsg(Ping)
      probe.expectMsg(Pong)
    }
  }
}

class DeferredStubbedSpec extends TypedAkkaSpec {

  import DeferredSpec._

  "must create underlying deferred behavior immediately" in {
    val inbox = TestInbox[Event]("evt")
    val behv = Behaviors.deferred[Command] { _ ⇒
      inbox.ref ! Started
      target(inbox.ref)
    }
    BehaviorTestkit(behv)
    // it's supposed to be created immediately (not waiting for first message)
    inbox.receiveMsg() should ===(Started)
  }

  "must stop when exception from factory" in {
    val inbox = TestInbox[Event]("evt")
    val exc = new RuntimeException("simulated exc from factory") with NoStackTrace
    val behv = Behaviors.deferred[Command] { _ ⇒
      inbox.ref ! Started
      throw exc
    }
    intercept[RuntimeException] {
      BehaviorTestkit(behv)
    } should ===(exc)
    inbox.receiveMsg() should ===(Started)
  }

}

