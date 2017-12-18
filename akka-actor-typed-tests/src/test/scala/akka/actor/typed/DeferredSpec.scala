/**
 * Copyright (C) 2017 Lightbend Inc. <http://www.lightbend.com>
 */
package akka.actor.typed

import scala.concurrent.Await
import scala.concurrent.duration._
import scala.util.control.NoStackTrace
import akka.actor.typed.scaladsl.Actor
import akka.actor.typed.scaladsl.Actor.BehaviorDecorators
import akka.actor.typed.scaladsl.AskPattern._
import akka.typed.testkit.{ EffectfulActorContext, Inbox, TestKitSettings }
import akka.typed.testkit.scaladsl._

object DeferredSpec {
  sealed trait Command
  case object Ping extends Command

  sealed trait Event
  case object Pong extends Event
  case object Started extends Event

  def target(monitor: ActorRef[Event]): Behavior[Command] =
    Actor.immutable((ctx, cmd) ⇒ cmd match {
      case Ping ⇒
        monitor ! Pong
        Actor.same
    })
}

class DeferredSpec extends TypedSpec with StartSupport {

  import DeferredSpec._
  implicit val testSettings = TestKitSettings(system)

  "Deferred behaviour" must {
    "must create underlying" in {
      val probe = TestProbe[Event]("evt")
      val behv = Actor.deferred[Command] { _ ⇒
        probe.ref ! Started
        target(probe.ref)
      }
      probe.expectNoMsg(100.millis) // not yet
      start(behv)
      // it's supposed to be created immediately (not waiting for first message)
      probe.expectMsg(Started)
    }

    "must stop when exception from factory" in {
      val probe = TestProbe[Event]("evt")
      val behv = Actor.deferred[Command] { ctx ⇒
        val child = ctx.spawnAnonymous(Actor.deferred[Command] { _ ⇒
          probe.ref ! Started
          throw new RuntimeException("simulated exc from factory") with NoStackTrace
        })
        ctx.watch(child)
        Actor.immutable[Command]((_, _) ⇒ Actor.same).onSignal {
          case (_, Terminated(`child`)) ⇒
            probe.ref ! Pong
            Actor.stopped
        }
      }
      start(behv)
      probe.expectMsg(Started)
      probe.expectMsg(Pong)
    }

    "must stop when deferred result it Stopped" in {
      val probe = TestProbe[Event]("evt")
      val behv = Actor.deferred[Command] { ctx ⇒
        val child = ctx.spawnAnonymous(Actor.deferred[Command](_ ⇒ Actor.stopped))
        ctx.watch(child)
        Actor.immutable[Command]((_, _) ⇒ Actor.same).onSignal {
          case (_, Terminated(`child`)) ⇒
            probe.ref ! Pong
            Actor.stopped
        }
      }
      start(behv)
      probe.expectMsg(Pong)
    }

    "must create underlying when nested" in {
      val probe = TestProbe[Event]("evt")
      val behv = Actor.deferred[Command] { _ ⇒
        Actor.deferred[Command] { _ ⇒
          probe.ref ! Started
          target(probe.ref)
        }
      }
      start(behv)
      probe.expectMsg(Started)
    }

    "must undefer underlying when wrapped by widen" in {
      val probe = TestProbe[Event]("evt")
      val behv = Actor.deferred[Command] { _ ⇒
        probe.ref ! Started
        target(probe.ref)
      }.widen[Command] {
        case m ⇒ m
      }
      probe.expectNoMsg(100.millis) // not yet
      val ref = start(behv)
      // it's supposed to be created immediately (not waiting for first message)
      probe.expectMsg(Started)
      ref ! Ping
      probe.expectMsg(Pong)
    }

    "must undefer underlying when wrapped by monitor" in {
      // monitor is implemented with tap, so this is testing both
      val probe = TestProbe[Event]("evt")
      val monitorProbe = TestProbe[Command]("monitor")
      val behv = Actor.monitor(monitorProbe.ref, Actor.deferred[Command] { _ ⇒
        probe.ref ! Started
        target(probe.ref)
      })
      probe.expectNoMsg(100.millis) // not yet
      val ref = start(behv)
      // it's supposed to be created immediately (not waiting for first message)
      probe.expectMsg(Started)
      ref ! Ping
      monitorProbe.expectMsg(Ping)
      probe.expectMsg(Pong)
    }
  }

}

class DeferredStubbedSpec extends TypedSpec {

  import DeferredSpec._

  def mkCtx(behv: Behavior[Command]): EffectfulActorContext[Command] =
    new EffectfulActorContext("ctx", behv, 1000, system)

  "must create underlying deferred behavior immediately" in {
    val inbox = Inbox[Event]("evt")
    val behv = Actor.deferred[Command] { _ ⇒
      inbox.ref ! Started
      target(inbox.ref)
    }
    val ctx = mkCtx(behv)
    // it's supposed to be created immediately (not waiting for first message)
    inbox.receiveMsg() should ===(Started)
  }

  "must stop when exception from factory" in {
    val inbox = Inbox[Event]("evt")
    val exc = new RuntimeException("simulated exc from factory") with NoStackTrace
    val behv = Actor.deferred[Command] { _ ⇒
      inbox.ref ! Started
      throw exc
    }
    intercept[RuntimeException] {
      mkCtx(behv)
    } should ===(exc)
    inbox.receiveMsg() should ===(Started)
  }

}

