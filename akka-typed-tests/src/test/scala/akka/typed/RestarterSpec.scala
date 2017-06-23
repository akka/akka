/**
 * Copyright (C) 2017 Lightbend Inc. <http://www.lightbend.com>
 */
package akka.typed

import scala.concurrent.duration._
import akka.typed.scaladsl.Actor._
import akka.typed.testkit.{ EffectfulActorContext, Inbox, TestKitSettings }

import scala.util.control.NoStackTrace
import akka.typed.testkit.scaladsl._

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
    immutable[Command] { (ctx, cmd) ⇒
      cmd match {
        case Ping ⇒
          monitor ! Pong
          same
        case NextState ⇒
          target(monitor, state.copy(n = state.n + 1))
        case GetState ⇒
          val reply = state.copy(children = ctx.children.map(c ⇒ c.path.name → c.upcast[Command]).toMap)
          monitor ! reply
          same
        case CreateChild(childBehv, childName) ⇒
          ctx.spawn(childBehv, childName)
          same
        case Throw(e) ⇒
          throw e
      }
    } onSignal {
      case (ctx, sig) ⇒
        monitor ! GotSignal(sig)
        same
    }

  class FailingConstructor(monitor: ActorRef[Event]) extends MutableBehavior[Command] {
    monitor ! Started
    throw new RuntimeException("simulated exc from constructor") with NoStackTrace

    override def onMessage(msg: Command): Behavior[Command] = {
      monitor ! Pong
      same
    }
  }

  trait StubbedTests {
    def system: ActorSystem[TypedSpec.Command]

    def mkCtx(behv: Behavior[Command]): EffectfulActorContext[Command] =
      new EffectfulActorContext("ctx", behv, 1000, system)

    def `must receive message`(): Unit = {
      val inbox = Inbox[Event]("evt")
      val behv = supervise(target(inbox.ref)).onFailure[Throwable](SupervisorStrategy.restart)
      val ctx = mkCtx(behv)
      ctx.run(Ping)
      inbox.receiveMsg() should ===(Pong)
    }

    def `must stop when no supervise`(): Unit = {
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
      val behv = supervise(target(inbox.ref)).onFailure[Exc1](SupervisorStrategy.restart)
      val ctx = mkCtx(behv)
      intercept[Exc3] {
        ctx.run(Throw(new Exc3))
      }
      inbox.receiveMsg() should ===(GotSignal(PostStop))
    }

    def `must restart when handled exception`(): Unit = {
      val inbox = Inbox[Event]("evt")
      val behv = supervise(target(inbox.ref)).onFailure[Exc1](SupervisorStrategy.restart)
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
      val behv = supervise(target(inbox.ref)).onFailure[Exc1](SupervisorStrategy.resume)
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
      val behv =
        supervise(
          supervise(
            target(inbox.ref)
          ).onFailure[Exc2](SupervisorStrategy.resume)
        ).onFailure[Exc3](SupervisorStrategy.restart)
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
      val behv = supervise(target(inbox.ref)).onFailure[Throwable](SupervisorStrategy.restart)
      val ctx = mkCtx(behv)
      intercept[StackOverflowError] {
        ctx.run(Throw(new StackOverflowError))
      }
      inbox.receiveAll() should ===(Nil)
    }

    def `must stop after restart retries limit`(): Unit = {
      val inbox = Inbox[Event]("evt")
      val strategy = SupervisorStrategy.restartWithLimit(maxNrOfRetries = 2, withinTimeRange = 1.minute)
      val behv = supervise(target(inbox.ref)).onFailure[Exc1](strategy)
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
      val behv = supervise(target(inbox.ref)).onFailure[Exc1](strategy)
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
      val behv = supervise(target(inbox.ref)).onFailure[Exc1](strategy)
      val ctx = mkCtx(behv)
      intercept[Exc1] {
        ctx.run(Throw(new Exc1))
      }
      inbox.receiveMsg() should ===(GotSignal(PostStop))
    }

    def `must create underlying deferred behavior immediately`(): Unit = {
      val inbox = Inbox[Event]("evt")
      val behv = supervise(deferred[Command] { _ ⇒
        inbox.ref ! Started
        target(inbox.ref)
      }).onFailure[Exc1](SupervisorStrategy.restart)
      val ctx = mkCtx(behv)
      // it's supposed to be created immediately (not waiting for first message)
      inbox.receiveMsg() should ===(Started)
    }
  }

  trait RealTests extends StartSupport {
    import akka.typed.scaladsl.adapter._
    implicit def system: ActorSystem[TypedSpec.Command]
    implicit val testSettings = TestKitSettings(system)

    def `must receive message`(): Unit = {
      val probe = TestProbe[Event]("evt")
      val behv = supervise(target(probe.ref)).onFailure[Throwable](SupervisorStrategy.restart)
      val ref = start(behv)
      ref ! Ping
      probe.expectMsg(Pong)
    }

    def `must stop when no supervise`(): Unit = {
      val probe = TestProbe[Event]("evt")
      val behv = target(probe.ref)
      val ref = start(behv)
      ref ! Throw(new Exc3)

      probe.expectMsg(GotSignal(PostStop))
    }

    def `must stop when unhandled exception`(): Unit = {
      val probe = TestProbe[Event]("evt")
      val behv = supervise(target(probe.ref)).onFailure[Exc1](SupervisorStrategy.restart)
      val ref = start(behv)
      ref ! Throw(new Exc3)
      probe.expectMsg(GotSignal(PostStop))
    }

    def `must restart when handled exception`(): Unit = {
      val probe = TestProbe[Event]("evt")
      val behv = supervise(target(probe.ref)).onFailure[Exc1](SupervisorStrategy.restart)
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
      val behv = supervise(target(probe.ref)).onFailure[Exc1](SupervisorStrategy.restart)
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
      val behv = supervise(target(probe.ref)).onFailure[Exc1](SupervisorStrategy.resume)
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
      val behv = supervise(
        supervise(target(probe.ref)).onFailure[Exc2](SupervisorStrategy.resume)
      ).onFailure[Exc3](SupervisorStrategy.restart)
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
      val behv = supervise(deferred[Command] { _ ⇒
        startedProbe.ref ! Started
        target(probe.ref)
      }).onFailure[Exception](strategy)
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
      val behv = supervise(target(probe.ref)).onFailure[Exc1](strategy)
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
      val behv = supervise(deferred[Command] { _ ⇒
        probe.ref ! Started
        target(probe.ref)
      }).onFailure[Exception](SupervisorStrategy.restart)
      probe.expectNoMsg(100.millis) // not yet
      start(behv)
      // it's supposed to be created immediately (not waiting for first message)
      probe.expectMsg(Started)
    }

    def `must stop when exception from MutableBehavior constructor`(): Unit = {
      val probe = TestProbe[Event]("evt")
      val behv = supervise(mutable[Command](_ ⇒ new FailingConstructor(probe.ref))).onFailure[Exception](SupervisorStrategy.restart)
      val ref = start(behv)
      probe.expectMsg(Started)
      ref ! Ping
      probe.expectNoMsg(100.millis)
    }

  }

  object `A restarter (stubbed, native)` extends StubbedTests with NativeSystem
  object `A restarter (stubbed, adapted)` extends StubbedTests with AdaptedSystem

  object `A restarter (real, native)` extends RealTests with NativeSystem
  object `A restarter (real, adapted)` extends RealTests with AdaptedSystem

}
