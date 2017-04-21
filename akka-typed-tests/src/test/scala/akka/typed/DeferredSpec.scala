/**
 * Copyright (C) 2017 Lightbend Inc. <http://www.lightbend.com>
 */
package akka.typed

import scala.concurrent.Await
import scala.concurrent.duration._
import scala.util.control.NoStackTrace

import akka.typed.scaladsl.Actor._
import akka.typed.scaladsl.AskPattern._
import akka.typed.testkit.EffectfulActorContext
import akka.typed.testkit.TestKitSettings
import akka.typed.testkit.scaladsl._

@org.junit.runner.RunWith(classOf[org.scalatest.junit.JUnitRunner])
class DeferredSpec extends TypedSpec {

  sealed trait Command
  case object Ping extends Command

  sealed trait Event
  case object Pong extends Event
  case object Started extends Event

  def target(monitor: ActorRef[Event]): Behavior[Command] =
    Immutable((ctx, cmd) ⇒ cmd match {
      case Ping ⇒
        monitor ! Pong
        Same
    })

  trait StubbedTests {
    def system: ActorSystem[TypedSpec.Command]

    def mkCtx(behv: Behavior[Command]): EffectfulActorContext[Command] =
      new EffectfulActorContext("ctx", behv, 1000, system)

    def `must create underlying deferred behavior immediately`(): Unit = {
      val inbox = Inbox[Event]("evt")
      val behv = Deferred[Command] { _ ⇒
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
      val behv = Deferred[Command] { _ ⇒
        inbox.ref ! Started
        throw exc
      }
      intercept[RuntimeException] {
        mkCtx(behv)
      } should ===(exc)
      inbox.receiveMsg() should ===(Started)
    }

  }

  trait RealTests {
    implicit def system: ActorSystem[TypedSpec.Command]
    implicit val testSettings = TestKitSettings(system)

    val nameCounter = Iterator.from(0)
    def nextName(): String = s"a-${nameCounter.next()}"

    def start(behv: Behavior[Command]): ActorRef[Command] =
      Await.result(system ? TypedSpec.Create(behv, nextName()), 3.seconds.dilated)

    def `must create underlying`(): Unit = {
      val probe = TestProbe[Event]("evt")
      val behv = Deferred[Command] { _ ⇒
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
      val behv = Deferred[Command] { _ ⇒
        probe.ref ! Started
        throw new RuntimeException("simulated exc from factory") with NoStackTrace
      }
      val ref = start(behv)
      probe.expectMsg(Started)
      ref ! Ping
      probe.expectNoMsg(100.millis)
    }

  }

  object `A Restarter (stubbed, native)` extends StubbedTests with NativeSystem
  object `A Restarter (stubbed, adapted)` extends StubbedTests with AdaptedSystem

  object `A Restarter (real, native)` extends RealTests with NativeSystem
  object `A Restarter (real, adapted)` extends RealTests with AdaptedSystem

}
