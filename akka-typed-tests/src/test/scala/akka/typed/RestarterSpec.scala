/**
 * Copyright (C) 2017 Lightbend Inc. <http://www.lightbend.com>
 */
package akka.typed

import scala.concurrent.duration._
import akka.typed.scaladsl.Actor._
import akka.typed.testkit.EffectfulActorContext
import scala.util.control.NoStackTrace
import akka.typed.testkit.TestKitSettings
import akka.typed.testkit.scaladsl._
import akka.typed.scaladsl.AskPattern._
import scala.concurrent.Await

@org.junit.runner.RunWith(classOf[org.scalatest.junit.JUnitRunner])
class RestarterSpec extends TypedSpec {

  sealed trait Command
  case object Ping extends Command
  case class Throw(e: Throwable) extends Command
  case object NextState extends Command
  case object GetState extends Command
  case class CreateChild[T](behavior: Behavior[T], name: String) extends Command

  sealed trait Event
  case object Pong extends Event
  case class GotSignal(signal: Signal) extends Event
  case class State(n: Int, children: Map[String, ActorRef[Command]]) extends Event
  case object Started extends Event

  class Exc1(msg: String = "exc-1") extends RuntimeException(msg) with NoStackTrace
  class Exc2 extends Exc1("exc-2")
  class Exc3(msg: String = "exc-3") extends RuntimeException(msg) with NoStackTrace

  def target(monitor: ActorRef[Event], state: State = State(0, Map.empty)): Behavior[Command] =
    Immutable[Command] { (ctx, cmd) ⇒
      cmd match {
        case Ping ⇒
          monitor ! Pong
          Same
        case NextState ⇒
          target(monitor, state.copy(n = state.n + 1))
        case GetState ⇒
          val reply = state.copy(children = ctx.children.map(c ⇒ c.path.name → c.upcast[Command]).toMap)
          monitor ! reply
          Same
        case CreateChild(childBehv, childName) ⇒
          ctx.spawn(childBehv, childName)
          Same
        case Throw(e) ⇒
          throw e
      }
    }.onSignal {
      case (ctx, sig) ⇒
        monitor ! GotSignal(sig)
        Same
    }

  class FailingConstructor(monitor: ActorRef[Event]) extends MutableBehavior[Command] {
    monitor ! Started
    throw new RuntimeException("simulated exc from constructor") with NoStackTrace

    override def onMessage(msg: Command): Behavior[Command] = {
      monitor ! Pong
      Same
    }
  }

  trait StubbedTests {
    def system: ActorSystem[TypedSpec.Command]

    def mkCtx(behv: Behavior[Command]): EffectfulActorContext[Command] =
      new EffectfulActorContext("ctx", behv, 1000, system)

    def `must receive message`(): Unit = {
      val inbox = Inbox[Event]("evt")
      val behv = Restarter[Throwable]().wrap(target(inbox.ref))
      val ctx = mkCtx(behv)
      ctx.run(Ping)
      inbox.receiveMsg() should ===(Pong)
    }

    def `must stop when no restarter`(): Unit = {
      val inbox = Inbox[Event]("evt")
      val behv = target(inbox.ref)
      val ctx = mkCtx(behv)
      intercept[Exc3] {
        ctx.run(Throw(new Exc3))
      }
      inbox.receiveMsg() should ===(GotSignal(PostStop))
    }

    def `must stop when unhandled exception`(): Unit = {
      val inbox = Inbox[Event]("evt")
      val behv = Restarter[Exc1]().wrap(target(inbox.ref))
      val ctx = mkCtx(behv)
      intercept[Exc3] {
        ctx.run(Throw(new Exc3))
      }
      inbox.receiveMsg() should ===(GotSignal(PostStop))
    }

    def `must restart when handled exception`(): Unit = {
      val inbox = Inbox[Event]("evt")
      val behv = Restarter[Exc1]().wrap(target(inbox.ref))
      val ctx = mkCtx(behv)
      ctx.run(NextState)
      ctx.run(GetState)
      inbox.receiveMsg() should ===(State(1, Map.empty))

      ctx.run(Throw(new Exc2))
      inbox.receiveMsg() should ===(GotSignal(PreRestart))
      ctx.run(GetState)
      inbox.receiveMsg() should ===(State(0, Map.empty))
    }

    def `must resume when handled exception`(): Unit = {
      val inbox = Inbox[Event]("evt")
      val behv = Restarter[Exc1](SupervisorStrategy.resume).wrap(target(inbox.ref))
      val ctx = mkCtx(behv)
      ctx.run(NextState)
      ctx.run(GetState)
      inbox.receiveMsg() should ===(State(1, Map.empty))

      ctx.run(Throw(new Exc2))
      ctx.run(GetState)
      inbox.receiveMsg() should ===(State(1, Map.empty))
    }

    def `must support nesting to handle different exceptions`(): Unit = {
      val inbox = Inbox[Event]("evt")
      val behv = Restarter[Exc3]().wrap(Restarter[Exc2](SupervisorStrategy.resume).wrap(target(inbox.ref)))
      val ctx = mkCtx(behv)
      ctx.run(NextState)
      ctx.run(GetState)
      inbox.receiveMsg() should ===(State(1, Map.empty))

      // resume
      ctx.run(Throw(new Exc2))
      ctx.run(GetState)
      inbox.receiveMsg() should ===(State(1, Map.empty))

      // restart
      ctx.run(Throw(new Exc3))
      inbox.receiveMsg() should ===(GotSignal(PreRestart))
      ctx.run(GetState)
      inbox.receiveMsg() should ===(State(0, Map.empty))

      // stop
      intercept[Exc1] {
        ctx.run(Throw(new Exc1))
      }
      inbox.receiveMsg() should ===(GotSignal(PostStop))
    }

    def `must not catch fatal error`(): Unit = {
      val inbox = Inbox[Event]("evt")
      val behv = Restarter[Throwable]().wrap(target(inbox.ref))
      val ctx = mkCtx(behv)
      intercept[StackOverflowError] {
        ctx.run(Throw(new StackOverflowError))
      }
      inbox.receiveAll() should ===(Nil)
    }

    def `must stop after restart retries limit`(): Unit = {
      val inbox = Inbox[Event]("evt")
      val strategy = SupervisorStrategy.restartWithLimit(maxNrOfRetries = 2, withinTimeRange = 1.minute)
      val behv = Restarter[Exc1](strategy).wrap(target(inbox.ref))
      val ctx = mkCtx(behv)
      ctx.run(Throw(new Exc1))
      inbox.receiveMsg() should ===(GotSignal(PreRestart))
      ctx.run(Throw(new Exc1))
      inbox.receiveMsg() should ===(GotSignal(PreRestart))
      intercept[Exc1] {
        ctx.run(Throw(new Exc1))
      }
      inbox.receiveMsg() should ===(GotSignal(PostStop))
    }

    def `must reset retry limit after withinTimeRange`(): Unit = {
      val inbox = Inbox[Event]("evt")
      val withinTimeRange = 2.seconds
      val strategy = SupervisorStrategy.restartWithLimit(maxNrOfRetries = 2, withinTimeRange)
      val behv = Restarter[Exc1](strategy).wrap(target(inbox.ref))
      val ctx = mkCtx(behv)
      ctx.run(Throw(new Exc1))
      inbox.receiveMsg() should ===(GotSignal(PreRestart))
      ctx.run(Throw(new Exc1))
      inbox.receiveMsg() should ===(GotSignal(PreRestart))
      Thread.sleep((2.seconds + 100.millis).toMillis)

      ctx.run(Throw(new Exc1))
      inbox.receiveMsg() should ===(GotSignal(PreRestart))
      ctx.run(Throw(new Exc1))
      inbox.receiveMsg() should ===(GotSignal(PreRestart))
      intercept[Exc1] {
        ctx.run(Throw(new Exc1))
      }
      inbox.receiveMsg() should ===(GotSignal(PostStop))
    }

    def `must stop at first exception when restart retries limit is 0`(): Unit = {
      val inbox = Inbox[Event]("evt")
      val strategy = SupervisorStrategy.restartWithLimit(maxNrOfRetries = 0, withinTimeRange = 1.minute)
      val behv = Restarter[Exc1](strategy).wrap(target(inbox.ref))
      val ctx = mkCtx(behv)
      intercept[Exc1] {
        ctx.run(Throw(new Exc1))
      }
      inbox.receiveMsg() should ===(GotSignal(PostStop))
    }

    def `must create underlying deferred behavior immediately`(): Unit = {
      val inbox = Inbox[Event]("evt")
      val behv = Restarter[Exception]().wrap(Deferred[Command] { _ ⇒
        inbox.ref ! Started
        target(inbox.ref)
      })
      val ctx = mkCtx(behv)
      // it's supposed to be created immediately (not waiting for first message)
      inbox.receiveMsg() should ===(Started)
    }
  }

  trait RealTests {
    import akka.typed.scaladsl.adapter._
    implicit def system: ActorSystem[TypedSpec.Command]
    implicit val testSettings = TestKitSettings(system)

    val nameCounter = Iterator.from(0)
    def nextName(): String = s"a-${nameCounter.next()}"

    def start(behv: Behavior[Command]): ActorRef[Command] =
      Await.result(system ? TypedSpec.Create(behv, nextName()), 3.seconds.dilated)

    def `must receive message`(): Unit = {
      val probe = TestProbe[Event]("evt")
      val behv = Restarter[Throwable]().wrap(target(probe.ref))
      val ref = start(behv)
      ref ! Ping
      probe.expectMsg(Pong)
    }

    def `must stop when no restarter`(): Unit = {
      val probe = TestProbe[Event]("evt")
      val behv = target(probe.ref)
      val ref = start(behv)
      ref ! Throw(new Exc3)

      probe.expectMsg(GotSignal(PostStop))
    }

    def `must stop when unhandled exception`(): Unit = {
      val probe = TestProbe[Event]("evt")
      val behv = Restarter[Exc1]().wrap(target(probe.ref))
      val ref = start(behv)
      ref ! Throw(new Exc3)
      probe.expectMsg(GotSignal(PostStop))
    }

    def `must restart when handled exception`(): Unit = {
      val probe = TestProbe[Event]("evt")
      val behv = Restarter[Exc1]().wrap(target(probe.ref))
      val ref = start(behv)
      ref ! NextState
      ref ! GetState
      probe.expectMsg(State(1, Map.empty))

      ref ! Throw(new Exc2)
      probe.expectMsg(GotSignal(PreRestart))
      ref ! GetState
      probe.expectMsg(State(0, Map.empty))
    }

    def `must NOT stop children when restarting`(): Unit = {
      val probe = TestProbe[Event]("evt")
      val behv = Restarter[Exc1]().wrap(target(probe.ref))
      val ref = start(behv)

      val childProbe = TestProbe[Event]("childEvt")
      val childName = nextName()
      ref ! CreateChild(target(childProbe.ref), childName)
      ref ! GetState
      probe.expectMsgType[State].children.keySet should contain(childName)

      ref ! Throw(new Exc1)
      probe.expectMsg(GotSignal(PreRestart))
      ref ! GetState
      // TODO document this difference compared to classic actors, and that
      //      children can be stopped if needed in PreRestart
      probe.expectMsgType[State].children.keySet should contain(childName)
    }

    def `must resume when handled exception`(): Unit = {
      val probe = TestProbe[Event]("evt")
      val behv = Restarter[Exc1](SupervisorStrategy.resume).wrap(target(probe.ref))
      val ref = start(behv)
      ref ! NextState
      ref ! GetState
      probe.expectMsg(State(1, Map.empty))

      ref ! Throw(new Exc2)
      ref ! GetState
      probe.expectMsg(State(1, Map.empty))
    }

    def `must support nesting to handle different exceptions`(): Unit = {
      val probe = TestProbe[Event]("evt")
      val behv = Restarter[Exc3]().wrap(Restarter[Exc2](SupervisorStrategy.resume).wrap(target(probe.ref)))
      val ref = start(behv)
      ref ! NextState
      ref ! GetState
      probe.expectMsg(State(1, Map.empty))

      // resume
      ref ! Throw(new Exc2)
      ref ! GetState
      probe.expectMsg(State(1, Map.empty))

      // restart
      ref ! Throw(new Exc3)
      probe.expectMsg(GotSignal(PreRestart))
      ref ! GetState
      probe.expectMsg(State(0, Map.empty))

      // stop
      ref ! Throw(new Exc1)
      probe.expectMsg(GotSignal(PostStop))
    }

    def `must restart after exponential backoff`(): Unit = {
      val probe = TestProbe[Event]("evt")
      val startedProbe = TestProbe[Event]("started")
      val minBackoff = 1.seconds
      val strategy = SupervisorStrategy.restartWithBackoff(minBackoff, 10.seconds, 0.0)
        .withResetBackoffAfter(10.seconds)
      val behv = Restarter[Exc1](strategy).wrap(Deferred[Command] { _ ⇒
        startedProbe.ref ! Started
        target(probe.ref)
      })
      val ref = start(behv)

      startedProbe.expectMsg(Started)
      ref ! NextState
      ref ! Throw(new Exc1)
      probe.expectMsg(GotSignal(PreRestart))
      ref ! Ping // dropped due to backoff

      startedProbe.expectNoMsg(minBackoff - 100.millis)
      probe.expectNoMsg(minBackoff + 100.millis)
      startedProbe.expectMsg(Started)
      ref ! GetState
      probe.expectMsg(State(0, Map.empty))

      // one more time
      ref ! NextState
      ref ! Throw(new Exc1)
      probe.expectMsg(GotSignal(PreRestart))
      ref ! Ping // dropped due to backoff

      startedProbe.expectNoMsg((minBackoff * 2) - 100.millis)
      probe.expectNoMsg((minBackoff * 2) + 100.millis)
      startedProbe.expectMsg(Started)
      ref ! GetState
      probe.expectMsg(State(0, Map.empty))
    }

    def `must reset exponential backoff count after reset timeout`(): Unit = {
      val probe = TestProbe[Event]("evt")
      val minBackoff = 1.seconds
      val strategy = SupervisorStrategy.restartWithBackoff(minBackoff, 10.seconds, 0.0)
        .withResetBackoffAfter(100.millis)
      val behv = Restarter[Exc1](strategy).wrap(target(probe.ref))
      val ref = start(behv)

      ref ! NextState
      ref ! Throw(new Exc1)
      probe.expectMsg(GotSignal(PreRestart))
      ref ! Ping // dropped due to backoff

      probe.expectNoMsg(minBackoff + 100.millis)
      ref ! GetState
      probe.expectMsg(State(0, Map.empty))

      // one more time after the reset timeout
      probe.expectNoMsg(strategy.resetBackoffAfter + 100.millis)
      ref ! NextState
      ref ! Throw(new Exc1)
      probe.expectMsg(GotSignal(PreRestart))
      ref ! Ping // dropped due to backoff

      // backoff was reset, so restarted after the minBackoff
      probe.expectNoMsg(minBackoff + 100.millis)
      ref ! GetState
      probe.expectMsg(State(0, Map.empty))
    }

    def `must create underlying deferred behavior immediately`(): Unit = {
      val probe = TestProbe[Event]("evt")
      val behv = Restarter[Exception]().wrap(Deferred[Command] { _ ⇒
        probe.ref ! Started
        target(probe.ref)
      })
      probe.expectNoMsg(100.millis) // not yet
      start(behv)
      // it's supposed to be created immediately (not waiting for first message)
      probe.expectMsg(Started)
    }

    def `must stop when exception from MutableBehavior constructor`(): Unit = {
      val probe = TestProbe[Event]("evt")
      val behv = Restarter[Exception]().wrap(Mutable[Command](_ ⇒ new FailingConstructor(probe.ref)))
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
