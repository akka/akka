/**
 * Copyright (C) 2017-2018 Lightbend Inc. <https://www.lightbend.com>
 */
package akka.actor.typed

import akka.actor.typed.scaladsl.Behaviors

import scala.concurrent.duration._
import akka.actor.typed.scaladsl.Behaviors._
import akka.testkit.typed.{ BehaviorTestkit, TestInbox, TestKit, TestKitSettings }

import scala.util.control.NoStackTrace
import akka.testkit.typed.scaladsl._
import org.scalatest.{ Matchers, WordSpec, fixture }

object SupervisionSpec {

  sealed trait Command
  case object Ping extends Command
  case class Throw(e: Throwable) extends Command
  case object IncrementState extends Command
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

  def targetBehavior(monitor: ActorRef[Event], state: State = State(0, Map.empty)): Behavior[Command] =
    immutable[Command] { (ctx, cmd) ⇒
      cmd match {
        case Ping ⇒
          monitor ! Pong
          Behaviors.same
        case IncrementState ⇒
          targetBehavior(monitor, state.copy(n = state.n + 1))
        case GetState ⇒
          val reply = state.copy(children = ctx.children.map(c ⇒ c.path.name → c.upcast[Command]).toMap)
          monitor ! reply
          Behaviors.same
        case CreateChild(childBehv, childName) ⇒
          ctx.spawn(childBehv, childName)
          Behaviors.same
        case Throw(e) ⇒
          throw e
      }
    } onSignal {
      case (_, sig) ⇒
        monitor ! GotSignal(sig)
        Behaviors.same
    }

  class FailingConstructor(monitor: ActorRef[Event]) extends MutableBehavior[Command] {
    monitor ! Started
    throw new RuntimeException("simulated exc from constructor") with NoStackTrace

    override def onMessage(msg: Command): Behavior[Command] = {
      monitor ! Pong
      Behaviors.same
    }
  }
}

class StubbedSupervisionSpec extends WordSpec with Matchers {

  import SupervisionSpec._

  def mkTestkit(behv: Behavior[Command]): BehaviorTestkit[Command] =
    BehaviorTestkit(behv)

  "A restarter (stubbed)" must {
    "receive message" in {
      val inbox = TestInbox[Event]("evt")
      val behv = supervise(targetBehavior(inbox.ref)).onFailure[Throwable](SupervisorStrategy.restart)
      val testkit = BehaviorTestkit(behv)
      testkit.run(Ping)
      inbox.receiveMsg() should ===(Pong)
    }

    "stop when no supervise" in {
      val inbox = TestInbox[Event]("evt")
      val behv = targetBehavior(inbox.ref)
      val testkit = BehaviorTestkit(behv)
      intercept[Exc3] {
        testkit.run(Throw(new Exc3))
      }
      inbox.receiveMsg() should ===(GotSignal(PostStop))
    }

    "stop when unhandled exception" in {
      val inbox = TestInbox[Event]("evt")
      val behv = supervise(targetBehavior(inbox.ref)).onFailure[Exc1](SupervisorStrategy.restart)
      val testkit = BehaviorTestkit(behv)
      intercept[Exc3] {
        testkit.run(Throw(new Exc3))
      }
      inbox.receiveMsg() should ===(GotSignal(PostStop))
    }

    "restart when handled exception" in {
      val inbox = TestInbox[Event]("evt")
      val behv = supervise(targetBehavior(inbox.ref)).onFailure[Exc1](SupervisorStrategy.restart)
      val testkit = BehaviorTestkit(behv)
      testkit.run(IncrementState)
      testkit.run(GetState)
      inbox.receiveMsg() should ===(State(1, Map.empty))

      testkit.run(Throw(new Exc2))
      inbox.receiveMsg() should ===(GotSignal(PreRestart))
      testkit.run(GetState)
      inbox.receiveMsg() should ===(State(0, Map.empty))
    }

    "resume when handled exception" in {
      val inbox = TestInbox[Event]("evt")
      val behv = supervise(targetBehavior(inbox.ref)).onFailure[Exc1](SupervisorStrategy.resume)
      val testkit = BehaviorTestkit(behv)
      testkit.run(IncrementState)
      testkit.run(GetState)
      inbox.receiveMsg() should ===(State(1, Map.empty))

      testkit.run(Throw(new Exc2))
      testkit.run(GetState)
      inbox.receiveMsg() should ===(State(1, Map.empty))
    }

    "support nesting to handle different exceptions" in {
      val inbox = TestInbox[Event]("evt")
      val behv =
        supervise(
          supervise(
            targetBehavior(inbox.ref)
          ).onFailure[Exc2](SupervisorStrategy.resume)
        ).onFailure[Exc3](SupervisorStrategy.restart)
      val testkit = BehaviorTestkit(behv)
      testkit.run(IncrementState)
      testkit.run(GetState)
      inbox.receiveMsg() should ===(State(1, Map.empty))

      // resume
      testkit.run(Throw(new Exc2))
      testkit.run(GetState)
      inbox.receiveMsg() should ===(State(1, Map.empty))

      // restart
      testkit.run(Throw(new Exc3))
      inbox.receiveMsg() should ===(GotSignal(PreRestart))
      testkit.run(GetState)
      inbox.receiveMsg() should ===(State(0, Map.empty))

      // stop
      intercept[Exc1] {
        testkit.run(Throw(new Exc1))
      }
      inbox.receiveMsg() should ===(GotSignal(PostStop))
    }

    "not catch fatal error" in {
      val inbox = TestInbox[Event]()
      val behv = Behaviors.supervise(targetBehavior(inbox.ref))
        .onFailure[Throwable](SupervisorStrategy.restart)
      val testkit = BehaviorTestkit(behv)
      intercept[StackOverflowError] {
        testkit.run(Throw(new StackOverflowError))
      }
      inbox.receiveAll() should ===(Nil)
    }

    "stop after restart retries limit" in {
      val inbox = TestInbox[Event]("evt")
      val strategy = SupervisorStrategy.restartWithLimit(maxNrOfRetries = 2, withinTimeRange = 1.minute)
      val behv = supervise(targetBehavior(inbox.ref)).onFailure[Exc1](strategy)
      val testkit = BehaviorTestkit(behv)
      testkit.run(Throw(new Exc1))
      inbox.receiveMsg() should ===(GotSignal(PreRestart))
      testkit.run(Throw(new Exc1))
      inbox.receiveMsg() should ===(GotSignal(PreRestart))
      intercept[Exc1] {
        testkit.run(Throw(new Exc1))
      }
      inbox.receiveMsg() should ===(GotSignal(PostStop))
    }

    "reset retry limit after withinTimeRange" in {
      val inbox = TestInbox[Event]("evt")
      val withinTimeRange = 2.seconds
      val strategy = SupervisorStrategy.restartWithLimit(maxNrOfRetries = 2, withinTimeRange)
      val behv = supervise(targetBehavior(inbox.ref)).onFailure[Exc1](strategy)
      val testkit = BehaviorTestkit(behv)
      testkit.run(Throw(new Exc1))
      inbox.receiveMsg() should ===(GotSignal(PreRestart))
      testkit.run(Throw(new Exc1))
      inbox.receiveMsg() should ===(GotSignal(PreRestart))
      Thread.sleep((2.seconds + 100.millis).toMillis)

      testkit.run(Throw(new Exc1))
      inbox.receiveMsg() should ===(GotSignal(PreRestart))
      testkit.run(Throw(new Exc1))
      inbox.receiveMsg() should ===(GotSignal(PreRestart))
      intercept[Exc1] {
        testkit.run(Throw(new Exc1))
      }
      inbox.receiveMsg() should ===(GotSignal(PostStop))
    }

    "stop at first exception when restart retries limit is 0" in {
      val inbox = TestInbox[Event]("evt")
      val strategy = SupervisorStrategy.restartWithLimit(maxNrOfRetries = 0, withinTimeRange = 1.minute)
      val behv = supervise(targetBehavior(inbox.ref))
        .onFailure[Exc1](strategy)
      val testkit = BehaviorTestkit(behv)
      intercept[Exc1] {
        testkit.run(Throw(new Exc1))
      }
      inbox.receiveMsg() should ===(GotSignal(PostStop))
    }

    "create underlying deferred behavior immediately" in {
      val inbox = TestInbox[Event]("evt")
      val behv = supervise(deferred[Command] { _ ⇒
        inbox.ref ! Started
        targetBehavior(inbox.ref)
      }).onFailure[Exc1](SupervisorStrategy.restart)
      mkTestkit(behv)
      // it's supposed to be created immediately (not waiting for first message)
      inbox.receiveMsg() should ===(Started)
    }
  }
}

class SupervisionSpec extends TestKit("SupervisionSpec") with TypedAkkaSpecWithShutdown {

  import SupervisionSpec._
  private val nameCounter = Iterator.from(0)
  private def nextName(prefix: String = "a"): String = s"$prefix-${nameCounter.next()}"

  implicit val testSettings = TestKitSettings(system)

  "A supervised actor" must {
    "receive message" in {
      val probe = TestProbe[Event]("evt")
      val behv = Behaviors.supervise(targetBehavior(probe.ref))
        .onFailure[Throwable](SupervisorStrategy.restart)
      val ref = spawn(behv)
      ref ! Ping
      probe.expectMsg(Pong)
    }

    "stop when not supervised" in {
      val probe = TestProbe[Event]("evt")
      val behv = targetBehavior(probe.ref)
      val ref = spawn(behv)
      ref ! Throw(new Exc3)

      probe.expectMsg(GotSignal(PostStop))
    }

    "stop when unhandled exception" in {
      val probe = TestProbe[Event]("evt")
      val behv = Behaviors.supervise(targetBehavior(probe.ref))
        .onFailure[Exc1](SupervisorStrategy.restart)
      val ref = spawn(behv)
      ref ! Throw(new Exc3)
      probe.expectMsg(GotSignal(PostStop))
    }

    "restart when handled exception" in {
      val probe = TestProbe[Event]("evt")
      val behv = Behaviors.supervise(targetBehavior(probe.ref))
        .onFailure[Exc1](SupervisorStrategy.restart)
      val ref = spawn(behv)
      ref ! IncrementState
      ref ! GetState
      probe.expectMsg(State(1, Map.empty))

      ref ! Throw(new Exc2)
      probe.expectMsg(GotSignal(PreRestart))
      ref ! GetState
      probe.expectMsg(State(0, Map.empty))
    }

    "NOT stop children when restarting" in {
      val parentProbe = TestProbe[Event]("evt")
      val behv = Behaviors.supervise(targetBehavior(parentProbe.ref))
        .onFailure[Exc1](SupervisorStrategy.restart)
      val ref = spawn(behv)

      val childProbe = TestProbe[Event]("childEvt")
      val childName = nextName()
      ref ! CreateChild(targetBehavior(childProbe.ref), childName)
      ref ! GetState
      parentProbe.expectMsgType[State].children.keySet should contain(childName)

      ref ! Throw(new Exc1)
      parentProbe.expectMsg(GotSignal(PreRestart))
      ref ! GetState
      // TODO document this difference compared to classic actors, and that
      //      children can be stopped if needed in PreRestart
      parentProbe.expectMsgType[State].children.keySet should contain(childName)
      childProbe.expectNoMessage()
    }

    "resume when handled exception" in {
      val probe = TestProbe[Event]("evt")
      val behv = supervise(targetBehavior(probe.ref)).onFailure[Exc1](SupervisorStrategy.resume)
      val ref = spawn(behv)
      ref ! IncrementState
      ref ! GetState
      probe.expectMsg(State(1, Map.empty))

      ref ! Throw(new Exc2)
      ref ! GetState
      probe.expectMsg(State(1, Map.empty))
    }

    "support nesting to handle different exceptions" in {
      val probe = TestProbe[Event]("evt")
      val behv = Behaviors.supervise(
        Behaviors.supervise(targetBehavior(probe.ref))
          .onFailure[Exc2](SupervisorStrategy.resume)
      ).onFailure[Exc3](SupervisorStrategy.restart)
      val ref = spawn(behv)
      ref ! IncrementState
      ref ! GetState
      probe.expectMsg(State(1, Map.empty))

      // resume
      ref ! Throw(new Exc2)
      probe.expectNoMessage()
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

    "restart after exponential backoff" in {
      val probe = TestProbe[Event]("evt")
      val startedProbe = TestProbe[Event]("started")
      val minBackoff = 1.seconds
      val strategy = SupervisorStrategy
        .restartWithBackoff(minBackoff, 10.seconds, 0.0)
        .withResetBackoffAfter(10.seconds)
      val behv = Behaviors.supervise(Behaviors.deferred[Command] { _ ⇒
        startedProbe.ref ! Started
        targetBehavior(probe.ref)
      }).onFailure[Exception](strategy)
      val ref = spawn(behv)

      startedProbe.expectMsg(Started)
      ref ! IncrementState
      ref ! Throw(new Exc1)
      probe.expectMsg(GotSignal(PreRestart))
      ref ! Ping // dropped due to backoff

      startedProbe.expectNoMessage(minBackoff - 100.millis)
      probe.expectNoMessage(minBackoff + 100.millis)
      startedProbe.expectMsg(Started)
      ref ! GetState
      probe.expectMsg(State(0, Map.empty))

      // one more time
      ref ! IncrementState
      ref ! Throw(new Exc1)
      probe.expectMsg(GotSignal(PreRestart))
      ref ! Ping // dropped due to backoff

      startedProbe.expectNoMessage((minBackoff * 2) - 100.millis)
      probe.expectNoMessage((minBackoff * 2) + 100.millis)
      startedProbe.expectMsg(Started)
      ref ! GetState
      probe.expectMsg(State(0, Map.empty))
    }

    "reset exponential backoff count after reset timeout" in {
      val probe = TestProbe[Event]("evt")
      val minBackoff = 1.seconds
      val strategy = SupervisorStrategy.restartWithBackoff(minBackoff, 10.seconds, 0.0)
        .withResetBackoffAfter(100.millis)
      val behv = supervise(targetBehavior(probe.ref)).onFailure[Exc1](strategy)
      val ref = spawn(behv)

      ref ! IncrementState
      ref ! Throw(new Exc1)
      probe.expectMsg(GotSignal(PreRestart))
      ref ! Ping // dropped due to backoff

      probe.expectNoMessage(minBackoff + 100.millis.dilated)
      ref ! GetState
      probe.expectMsg(State(0, Map.empty))

      // one more time after the reset timeout
      probe.expectNoMessage(strategy.resetBackoffAfter + 100.millis.dilated)
      ref ! IncrementState
      ref ! Throw(new Exc1)
      probe.expectMsg(GotSignal(PreRestart))
      ref ! Ping // dropped due to backoff

      // backoff was reset, so restarted after the minBackoff
      probe.expectNoMessage(minBackoff + 100.millis.dilated)
      ref ! GetState
      probe.expectMsg(State(0, Map.empty))
    }

    "create underlying deferred behavior immediately" in {
      val probe = TestProbe[Event]("evt")
      val behv = supervise(deferred[Command] { _ ⇒
        probe.ref ! Started
        targetBehavior(probe.ref)
      }).onFailure[Exception](SupervisorStrategy.restart)
      probe.expectNoMessage() // not yet
      spawn(behv)
      // it's supposed to be created immediately (not waiting for first message)
      probe.expectMsg(Started)
    }

    "stop when exception from MutableBehavior constructor" in {
      val probe = TestProbe[Event]("evt")
      val behv = supervise(mutable[Command](_ ⇒ new FailingConstructor(probe.ref)))
        .onFailure[Exception](SupervisorStrategy.restart)
      val ref = spawn(behv)
      probe.expectMsg(Started)
      ref ! Ping
      probe.expectNoMessage()
    }
  }
}
