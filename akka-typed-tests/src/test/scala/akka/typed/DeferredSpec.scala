/**
 * Copyright (C) 2017 Lightbend Inc. <http://www.lightbend.com>
 */
package akka.typed

import scala.concurrent.Await
import scala.concurrent.duration._
import scala.util.control.NoStackTrace
import akka.typed.scaladsl.Actor
import akka.typed.scaladsl.Actor.BehaviorDecorators
import akka.typed.scaladsl.AskPattern._
import akka.typed.testkit.{ EffectfulActorContext, Inbox, TestKitSettings }
import akka.typed.testkit.scaladsl._

@org.junit.runner.RunWith(classOf[org.scalatest.junit.JUnitRunner])
class DeferredSpec extends TypedSpec {

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

  trait StubbedTests {
    def system: ActorSystem[TypedSpec.Command]

    def mkCtx(behv: Behavior[Command]): EffectfulActorContext[Command] =
      new EffectfulActorContext("ctx", behv, 1000, system)

    def `must create underlying deferred behavior immediately`(): Unit = {
      val inbox = Inbox[Event]("evt")
      val behv = Actor.deferred[Command] { _ ⇒
        inbox.ref ! Started
        target(inbox.ref)
      }
      val ctx = mkCtx(behv)
      // it's supposed to be created immediately (not waiting for first message)
      inbox.receiveMsg() should ===(Started)
    }

    def `must stop when exception from factory`(): Unit = {
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

  trait RealTests extends StartSupport {
    implicit def system: ActorSystem[TypedSpec.Command]
    implicit val testSettings = TestKitSettings(system)

    def `must create underlying`(): Unit = {
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

    def `must stop when exception from factory`(): Unit = {
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

    def `must stop when deferred result it Stopped`(): Unit = {
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

    def `must create underlying when nested`(): Unit = {
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

    def `must undefer underlying when wrapped by widen`(): Unit = {
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

    def `must undefer underlying when wrapped by monitor`(): Unit = {
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

  object `A DeferredBehavior (stubbed, native)` extends StubbedTests with NativeSystem
  object `A DeferredBehavior (stubbed, adapted)` extends StubbedTests with AdaptedSystem

  object `A DeferredBehavior (real, native)` extends RealTests with NativeSystem
  object `A DeferredBehavior (real, adapted)` extends RealTests with AdaptedSystem

}
