/*
 * Copyright (C) 2017-2019 Lightbend Inc. <https://www.lightbend.com>
 */

package akka.actor.typed

import java.io.IOException
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.{ AtomicBoolean, AtomicInteger }

import akka.actor.ActorInitializationException
import akka.actor.typed.scaladsl.{ AbstractBehavior, Behaviors }
import akka.actor.typed.scaladsl.Behaviors._
import akka.testkit.EventFilter
import akka.actor.testkit.typed.scaladsl._
import akka.actor.testkit.typed._
import org.scalatest.{ Matchers, WordSpec, WordSpecLike }
import scala.util.control.NoStackTrace
import scala.concurrent.duration._

import akka.actor.typed.SupervisorStrategy.Resume

object SupervisionSpec {

  sealed trait Command
  final case class Ping(n: Int) extends Command
  final case class Throw(e: Throwable) extends Command
  case object IncrementState extends Command
  case object GetState extends Command
  final case class CreateChild[T](behavior: Behavior[T], name: String) extends Command
  final case class Watch(ref: ActorRef[_]) extends Command

  sealed trait Event
  final case class Pong(n: Int) extends Event
  final case class GotSignal(signal: Signal) extends Event
  final case class State(n: Int, children: Map[String, ActorRef[Command]]) extends Event
  case object Started extends Event
  case object StartFailed extends Event

  class Exc1(message: String = "exc-1") extends RuntimeException(message) with NoStackTrace
  class Exc2 extends Exc1("exc-2")
  class Exc3(message: String = "exc-3") extends RuntimeException(message) with NoStackTrace

  def targetBehavior(monitor: ActorRef[Event], state: State = State(0, Map.empty), slowStop: Option[CountDownLatch] = None): Behavior[Command] =
    receive[Command] { (context, cmd) ⇒
      cmd match {
        case Ping(n) ⇒
          monitor ! Pong(n)
          Behaviors.same
        case IncrementState ⇒
          targetBehavior(monitor, state.copy(n = state.n + 1), slowStop)
        case GetState ⇒
          val reply = state.copy(children = context.children.map(c ⇒ c.path.name → c.unsafeUpcast[Command]).toMap)
          monitor ! reply
          Behaviors.same
        case CreateChild(childBehv, childName) ⇒
          context.spawn(childBehv, childName)
          Behaviors.same
        case Watch(ref) ⇒
          context.watch(ref)
          Behaviors.same
        case Throw(e) ⇒
          throw e
      }
    } receiveSignal {
      case (_, sig) ⇒
        if (sig == PostStop)
          slowStop.foreach(latch ⇒ latch.await(10, TimeUnit.SECONDS))
        monitor ! GotSignal(sig)
        Behaviors.same
    }

  class FailingConstructor(monitor: ActorRef[Event]) extends AbstractBehavior[Command] {
    monitor ! Started
    throw new RuntimeException("simulated exc from constructor") with NoStackTrace

    override def onMessage(message: Command): Behavior[Command] = {
      monitor ! Pong(0)
      Behaviors.same
    }
  }
}

class StubbedSupervisionSpec extends WordSpec with Matchers {

  import SupervisionSpec._

  "A restarter (stubbed)" must {
    "receive message" in {
      val inbox = TestInbox[Event]("evt")
      val behv = supervise(targetBehavior(inbox.ref)).onFailure[Throwable](SupervisorStrategy.restart)
      val testkit = BehaviorTestKit(behv)
      testkit.run(Ping(1))
      inbox.receiveMessage() should ===(Pong(1))
    }

    "stop when no supervise" in {
      val inbox = TestInbox[Event]("evt")
      val behv = targetBehavior(inbox.ref)
      val testkit = BehaviorTestKit(behv)
      intercept[Exc3] {
        testkit.run(Throw(new Exc3))
      }
      inbox.receiveMessage() should ===(GotSignal(PostStop))
    }

    "stop when unhandled exception" in {
      val inbox = TestInbox[Event]("evt")
      val behv = supervise(targetBehavior(inbox.ref)).onFailure[Exc1](SupervisorStrategy.restart)
      val testkit = BehaviorTestKit(behv)
      intercept[Exc3] {
        testkit.run(Throw(new Exc3))
      }
      inbox.receiveMessage() should ===(GotSignal(PostStop))
    }

    "restart when handled exception" in {
      val inbox = TestInbox[Event]("evt")
      val behv = supervise(targetBehavior(inbox.ref)).onFailure[Exc1](SupervisorStrategy.restart)
      val testkit = BehaviorTestKit(behv)
      testkit.run(IncrementState)
      testkit.run(GetState)
      inbox.receiveMessage() should ===(State(1, Map.empty))

      testkit.run(Throw(new Exc2))
      inbox.receiveMessage() should ===(GotSignal(PreRestart))
      testkit.run(GetState)
      inbox.receiveMessage() should ===(State(0, Map.empty))
    }

    "resume when handled exception" in {
      val inbox = TestInbox[Event]("evt")
      val behv = supervise(targetBehavior(inbox.ref)).onFailure[Exc1](SupervisorStrategy.resume)
      val testkit = BehaviorTestKit(behv)
      testkit.run(IncrementState)
      testkit.run(GetState)
      inbox.receiveMessage() should ===(State(1, Map.empty))

      testkit.run(Throw(new Exc2))
      testkit.run(GetState)
      inbox.receiveMessage() should ===(State(1, Map.empty))
    }

    "support nesting to handle different exceptions" in {
      val inbox = TestInbox[Event]("evt")
      val behv =
        supervise(
          supervise(
            targetBehavior(inbox.ref)
          ).onFailure[Exc2](SupervisorStrategy.resume)
        ).onFailure[Exc3](SupervisorStrategy.restart)
      val testkit = BehaviorTestKit(behv)
      testkit.run(IncrementState)
      testkit.run(GetState)
      inbox.receiveMessage() should ===(State(1, Map.empty))

      // resume
      testkit.run(Throw(new Exc2))
      testkit.run(GetState)
      inbox.receiveMessage() should ===(State(1, Map.empty))

      // restart
      testkit.run(Throw(new Exc3))
      inbox.receiveMessage() should ===(GotSignal(PreRestart))
      testkit.run(GetState)
      inbox.receiveMessage() should ===(State(0, Map.empty))

      // stop
      intercept[Exc1] {
        testkit.run(Throw(new Exc1))
      }
      inbox.receiveMessage() should ===(GotSignal(PostStop))
    }

    "not catch fatal error" in {
      val inbox = TestInbox[Event]()
      val behv = Behaviors.supervise(targetBehavior(inbox.ref))
        .onFailure[Throwable](SupervisorStrategy.restart)
      val testkit = BehaviorTestKit(behv)
      intercept[StackOverflowError] {
        testkit.run(Throw(new StackOverflowError))
      }
      inbox.receiveAll() should ===(Nil)
    }

    "stop after restart retries limit" in {
      val inbox = TestInbox[Event]("evt")
      val strategy = SupervisorStrategy.restart.withLimit(maxNrOfRetries = 2, withinTimeRange = 1.minute)
      val behv = supervise(targetBehavior(inbox.ref)).onFailure[Exc1](strategy)
      val testkit = BehaviorTestKit(behv)
      testkit.run(Throw(new Exc1))
      inbox.receiveMessage() should ===(GotSignal(PreRestart))
      testkit.run(Throw(new Exc1))
      inbox.receiveMessage() should ===(GotSignal(PreRestart))
      intercept[Exc1] {
        testkit.run(Throw(new Exc1))
      }
      inbox.receiveMessage() should ===(GotSignal(PostStop))
    }

    "reset retry limit after withinTimeRange" in {
      val inbox = TestInbox[Event]("evt")
      val withinTimeRange = 2.seconds
      val strategy = SupervisorStrategy.restart.withLimit(maxNrOfRetries = 2, withinTimeRange)
      val behv = supervise(targetBehavior(inbox.ref)).onFailure[Exc1](strategy)
      val testkit = BehaviorTestKit(behv)
      testkit.run(Throw(new Exc1))
      inbox.receiveMessage() should ===(GotSignal(PreRestart))
      testkit.run(Throw(new Exc1))
      inbox.receiveMessage() should ===(GotSignal(PreRestart))
      Thread.sleep((2.seconds + 100.millis).toMillis)

      testkit.run(Throw(new Exc1))
      inbox.receiveMessage() should ===(GotSignal(PreRestart))
      testkit.run(Throw(new Exc1))
      inbox.receiveMessage() should ===(GotSignal(PreRestart))
      intercept[Exc1] {
        testkit.run(Throw(new Exc1))
      }
      inbox.receiveMessage() should ===(GotSignal(PostStop))
    }

    "stop at first exception when restart retries limit is 0" in {
      val inbox = TestInbox[Event]("evt")
      val strategy = SupervisorStrategy.restart.withLimit(maxNrOfRetries = 0, withinTimeRange = 1.minute)
      val behv = supervise(targetBehavior(inbox.ref))
        .onFailure[Exc1](strategy)
      val testkit = BehaviorTestKit(behv)
      intercept[Exc1] {
        testkit.run(Throw(new Exc1))
      }
      inbox.receiveMessage() should ===(GotSignal(PostStop))
    }

    "create underlying deferred behavior immediately" in {
      val inbox = TestInbox[Event]("evt")
      val behv = supervise(setup[Command] { _ ⇒
        inbox.ref ! Started
        targetBehavior(inbox.ref)
      }).onFailure[Exc1](SupervisorStrategy.restart)
      BehaviorTestKit(behv)
      // it's supposed to be created immediately (not waiting for first message)
      inbox.receiveMessage() should ===(Started)
    }
  }
}

class SupervisionSpec extends ScalaTestWithActorTestKit(
  """
    akka.loggers = [akka.testkit.TestEventListener]
    akka.log-dead-letters = off
    """) with WordSpecLike {

  import SupervisionSpec._
  import BehaviorInterceptor._

  private val nameCounter = Iterator.from(0)
  private def nextName(prefix: String = "a"): String = s"$prefix-${nameCounter.next()}"

  // FIXME eventfilter support in typed testkit
  import akka.actor.typed.scaladsl.adapter._

  implicit val untypedSystem = system.toUntyped

  class FailingConstructorTestSetup(failCount: Int) {
    val failCounter = new AtomicInteger(0)
    class FailingConstructor(monitor: ActorRef[Event]) extends AbstractBehavior[Command] {
      monitor ! Started
      if (failCounter.getAndIncrement() < failCount) {
        throw TestException("simulated exc from constructor")
      }
      override def onMessage(message: Command): Behavior[Command] = {
        monitor ! Pong(0)
        Behaviors.same
      }
    }
  }

  class FailingDeferredTestSetup(failCount: Int, strategy: SupervisorStrategy) {
    val probe = TestProbe[AnyRef]("evt")
    val failCounter = new AtomicInteger(0)
    def behv = supervise(setup[Command] { _ ⇒
      val count = failCounter.getAndIncrement()
      if (count < failCount) {
        probe.ref ! StartFailed
        throw TestException(s"construction ${count} failed")
      } else {
        probe.ref ! Started
        Behaviors.empty
      }
    }).onFailure[TestException](strategy)
  }

  class FailingUnhandledTestSetup(strategy: SupervisorStrategy) {
    val probe = TestProbe[AnyRef]("evt")
    def behv = supervise(setup[Command] { _ ⇒
      probe.ref ! StartFailed
      throw new TestException("construction failed")
    }).onFailure[IllegalArgumentException](strategy)
  }

  "A supervised actor" must {
    "receive message" in {
      val probe = TestProbe[Event]("evt")
      val behv = Behaviors.supervise(targetBehavior(probe.ref))
        .onFailure[Throwable](SupervisorStrategy.restart)
      val ref = spawn(behv)
      ref ! Ping(1)
      probe.expectMessage(Pong(1))
    }

    "stop when strategy is stop" in {
      val probe = TestProbe[Event]("evt")
      val behv = Behaviors.supervise(targetBehavior(probe.ref))
        .onFailure[Throwable](SupervisorStrategy.stop)
      val ref = spawn(behv)
      EventFilter[Exc3](occurrences = 1).intercept {
        ref ! Throw(new Exc3)
        probe.expectMessage(GotSignal(PostStop))
      }
    }

    "stop when strategy is stop - exception in setup" in {
      val probe = TestProbe[Event]("evt")
      val failedSetup = Behaviors.setup[Command](_ ⇒ {
        throw new Exc3()
        targetBehavior(probe.ref)
      })
      val behv = Behaviors.supervise(failedSetup).onFailure[Throwable](SupervisorStrategy.stop)
      EventFilter[Exc3](occurrences = 1).intercept {
        spawn(behv)
      }
    }

    "support nesting exceptions with different strategies" in {
      val probe = TestProbe[Event]("evt")
      val behv =
        supervise(
          supervise(targetBehavior(probe.ref))
            .onFailure[RuntimeException](SupervisorStrategy.stop)
        ).onFailure[Exception](SupervisorStrategy.restart)

      val ref = spawn(behv)

      EventFilter[IOException](occurrences = 1).intercept {
        ref ! Throw(new IOException())
        probe.expectMessage(GotSignal(PreRestart))
      }

      EventFilter[IllegalArgumentException](occurrences = 1).intercept {
        ref ! Throw(new IllegalArgumentException("cat"))
        probe.expectMessage(GotSignal(PostStop))
      }
    }

    "support nesting exceptions with outer restart and inner backoff strategies" in {
      val probe = TestProbe[Event]("evt")
      val behv =
        supervise(
          supervise(targetBehavior(probe.ref))
            .onFailure[IllegalArgumentException](SupervisorStrategy.restartWithBackoff(10.millis, 10.millis, 0.0))
        ).onFailure[IOException](SupervisorStrategy.restart)

      val ref = spawn(behv)

      EventFilter[Exception](occurrences = 1).intercept {
        ref ! Throw(new IOException())
        probe.expectMessage(GotSignal(PreRestart))
      }
      // verify that it's still alive and not stopped, IllegalStateException would stop it
      ref ! Ping(1)
      probe.expectMessage(Pong(1))

      EventFilter[IllegalArgumentException](occurrences = 1).intercept {
        ref ! Throw(new IllegalArgumentException("cat"))
        probe.expectMessage(GotSignal(PreRestart))
      }

      // verify that it's still alive and not stopped, IllegalStateException would stop it
      ref ! Ping(2)
      probe.expectMessage(Pong(2))
    }

    "support nesting exceptions with inner restart and outer backoff strategies" in {
      val probe = TestProbe[Event]("evt")
      val behv =
        supervise(
          supervise(targetBehavior(probe.ref))
            .onFailure[IllegalArgumentException](SupervisorStrategy.restart)
        ).onFailure[IOException](SupervisorStrategy.restartWithBackoff(10.millis, 10.millis, 0.0))

      val ref = spawn(behv)

      EventFilter[Exception](occurrences = 1).intercept {
        ref ! Throw(new IOException())
        probe.expectMessage(GotSignal(PreRestart))
      }
      // verify that it's still alive and not stopped, IllegalStateException would stop it
      ref ! Ping(1)
      probe.expectMessage(Pong(1))

      EventFilter[IllegalArgumentException](occurrences = 1).intercept {
        ref ! Throw(new IllegalArgumentException("cat"))
        probe.expectMessage(GotSignal(PreRestart))
      }

      // verify that it's still alive and not stopped, IllegalStateException would stop it
      ref ! Ping(2)
      probe.expectMessage(Pong(2))
    }

    "stop when not supervised" in {
      val probe = TestProbe[Event]("evt")
      val behv = targetBehavior(probe.ref)
      val ref = spawn(behv)
      EventFilter[Exc3](occurrences = 1).intercept {
        ref ! Throw(new Exc3)
        probe.expectMessage(GotSignal(PostStop))
      }
    }

    "stop when unhandled exception" in {
      val probe = TestProbe[Event]("evt")
      val behv = Behaviors.supervise(targetBehavior(probe.ref))
        .onFailure[Exc1](SupervisorStrategy.restart)
      val ref = spawn(behv)
      EventFilter[Exc3](occurrences = 1).intercept {
        ref ! Throw(new Exc3)
        probe.expectMessage(GotSignal(PostStop))
      }
    }

    "restart when handled exception" in {
      val probe = TestProbe[Event]("evt")
      val behv = Behaviors.supervise(targetBehavior(probe.ref))
        .onFailure[Exc1](SupervisorStrategy.restart)
      val ref = spawn(behv)
      ref ! IncrementState
      ref ! GetState
      probe.expectMessage(State(1, Map.empty))

      EventFilter[Exc2](occurrences = 1).intercept {
        ref ! Throw(new Exc2)
        probe.expectMessage(GotSignal(PreRestart))
      }
      ref ! GetState
      probe.expectMessage(State(0, Map.empty))
    }

    "stop when restart limit is hit" in {
      val probe = TestProbe[Event]("evt")
      val resetTimeout = 500.millis
      val behv = Behaviors.supervise(targetBehavior(probe.ref))
        .onFailure[Exc1](SupervisorStrategy.restart.withLimit(2, resetTimeout))
      val ref = spawn(behv)
      ref ! IncrementState
      ref ! GetState
      probe.expectMessage(State(1, Map.empty))

      EventFilter[Exc2](occurrences = 3).intercept {
        ref ! Throw(new Exc2)
        probe.expectMessage(GotSignal(PreRestart))
        ref ! Throw(new Exc2)
        probe.expectMessage(GotSignal(PreRestart))
        ref ! Throw(new Exc2)
        probe.expectMessage(GotSignal(PostStop))
      }
      EventFilter.warning(start = "received dead letter", occurrences = 1).intercept {
        ref ! GetState
        probe.expectNoMessage()
      }
    }

    "reset fixed limit after timeout" in {
      val probe = TestProbe[Event]("evt")
      val resetTimeout = 500.millis
      val behv = Behaviors.supervise(targetBehavior(probe.ref))
        .onFailure[Exc1](SupervisorStrategy.restart.withLimit(2, resetTimeout))
      val ref = spawn(behv)
      ref ! IncrementState
      ref ! GetState
      probe.expectMessage(State(1, Map.empty))

      EventFilter[Exc2](occurrences = 3).intercept {
        ref ! Throw(new Exc2)
        probe.expectMessage(GotSignal(PreRestart))
        ref ! Throw(new Exc2)
        probe.expectMessage(GotSignal(PreRestart))

        probe.expectNoMessage(resetTimeout + 50.millis)

        ref ! Throw(new Exc2)
        probe.expectMessage(GotSignal(PreRestart))
      }
      ref ! GetState
      probe.expectMessage(State(0, Map.empty))
    }

    "stop children when restarting" in {
      testStopChildren(strategy = SupervisorStrategy.restart)
    }

    "stop children when backoff" in {
      testStopChildren(strategy = SupervisorStrategy.restartWithBackoff(10.millis, 10.millis, 0))
    }

    def testStopChildren(strategy: SupervisorStrategy): Unit = {
      val parentProbe = TestProbe[Event]("evt")
      val behv = Behaviors.supervise(targetBehavior(parentProbe.ref))
        .onFailure[Exc1](strategy)
      val ref = spawn(behv)

      val anotherProbe = TestProbe[String]("another")
      ref ! Watch(anotherProbe.ref)

      val childProbe = TestProbe[Event]("childEvt")
      val slowStop = new CountDownLatch(1)
      val child1Name = nextName()
      val child2Name = nextName()
      ref ! CreateChild(targetBehavior(childProbe.ref, slowStop = Some(slowStop)), child1Name)
      ref ! CreateChild(targetBehavior(childProbe.ref, slowStop = Some(slowStop)), child2Name)
      ref ! GetState
      parentProbe.expectMessageType[State].children.keySet should ===(Set(child1Name, child2Name))

      EventFilter[Exc1](occurrences = 1).intercept {
        ref ! Throw(new Exc1)
        parentProbe.expectMessage(GotSignal(PreRestart))
        ref ! GetState
        anotherProbe.stop()
      }

      // waiting for children to stop, GetState stashed
      parentProbe.expectNoMessage()
      slowStop.countDown()

      childProbe.expectMessage(GotSignal(PostStop))
      childProbe.expectMessage(GotSignal(PostStop))
      parentProbe.expectMessageType[State].children.keySet should ===(Set.empty)
      // anotherProbe was stopped, Terminated signal stashed and delivered to new behavior
      parentProbe.expectMessage(GotSignal(Terminated(anotherProbe.ref)))
    }

    "optionally NOT stop children when restarting" in {
      testNotStopChildren(strategy = SupervisorStrategy.restart.withStopChildren(enabled = false))
    }

    "optionally NOT stop children when backoff" in {
      testNotStopChildren(strategy = SupervisorStrategy.restartWithBackoff(10.millis, 10.millis, 0)
        .withStopChildren(enabled = false))
    }

    def testNotStopChildren(strategy: SupervisorStrategy): Unit = {
      val parentProbe = TestProbe[Event]("evt")
      val behv = Behaviors.supervise(targetBehavior(parentProbe.ref))
        .onFailure[Exc1](strategy)
      val ref = spawn(behv)

      val childProbe = TestProbe[Event]("childEvt")
      val childName = nextName()
      ref ! CreateChild(targetBehavior(childProbe.ref), childName)
      ref ! GetState
      parentProbe.expectMessageType[State].children.keySet should contain(childName)

      EventFilter[Exc1](occurrences = 1).intercept {
        ref ! Throw(new Exc1)
        parentProbe.expectMessage(GotSignal(PreRestart))
        ref ! GetState
      }
      parentProbe.expectMessageType[State].children.keySet should contain(childName)
      childProbe.expectNoMessage()
    }

    "stop children when restarting second time during unstash" in {
      testStopChildrenWhenExceptionFromUnstash(SupervisorStrategy.restart)
    }

    "stop children when backoff second time during unstash" in {
      testStopChildrenWhenExceptionFromUnstash(
        SupervisorStrategy.restartWithBackoff(10.millis, 10.millis, 0))
    }

    def testStopChildrenWhenExceptionFromUnstash(strategy: SupervisorStrategy): Unit = {
      val parentProbe = TestProbe[Event]("evt")
      val behv = Behaviors.supervise(targetBehavior(parentProbe.ref))
        .onFailure[Exc1](strategy)
      val ref = spawn(behv)

      val childProbe = TestProbe[Event]("childEvt")
      val slowStop = new CountDownLatch(1)
      val child1Name = nextName()
      ref ! CreateChild(targetBehavior(childProbe.ref, slowStop = Some(slowStop)), child1Name)
      ref ! GetState
      parentProbe.expectMessageType[State].children.keySet should ===(Set(child1Name))

      val child2Name = nextName()

      EventFilter[Exc1](occurrences = 1).intercept {
        ref ! Throw(new Exc1)
        parentProbe.expectMessage(GotSignal(PreRestart))
        ref ! GetState
        ref ! CreateChild(targetBehavior(childProbe.ref), child2Name)
        ref ! GetState
        ref ! Throw(new Exc1)
      }

      EventFilter[Exc1](occurrences = 1).intercept {
        slowStop.countDown()
        childProbe.expectMessage(GotSignal(PostStop)) // child1
        parentProbe.expectMessageType[State].children.keySet should ===(Set.empty)
        parentProbe.expectMessageType[State].children.keySet should ===(Set(child2Name))
        // the stashed Throw is causing another restart and stop of child2
        childProbe.expectMessage(GotSignal(PostStop)) // child2
      }

      ref ! GetState
      parentProbe.expectMessageType[State].children.keySet should ===(Set.empty)
    }

    "stop children when restart (with limit) from exception in first setup" in {
      testStopChildrenWhenExceptionFromFirstSetup(SupervisorStrategy.restart.withLimit(10, 1.second))
    }

    "stop children when backoff from exception in first setup" in {
      testStopChildrenWhenExceptionFromFirstSetup(SupervisorStrategy.restartWithBackoff(10.millis, 10.millis, 0))
    }

    def testStopChildrenWhenExceptionFromFirstSetup(strategy: SupervisorStrategy): Unit = {
      val parentProbe = TestProbe[Event]("evt")
      val child1Probe = TestProbe[Event]("childEvt")
      val child2Probe = TestProbe[Event]("childEvt")
      val slowStop1 = new CountDownLatch(1)
      val slowStop2 = new CountDownLatch(1)
      val throwFromSetup = new AtomicBoolean(true)
      val behv = Behaviors.supervise {
        Behaviors.setup[Command] { ctx ⇒
          ctx.spawn(targetBehavior(child1Probe.ref, slowStop = Some(slowStop1)), "child1")
          if (throwFromSetup.get()) {
            // note that this second child waiting on slowStop2 will prevent a restart loop that could exhaust the
            // limit before throwFromSetup is set back to false
            ctx.spawn(targetBehavior(child2Probe.ref, slowStop = Some(slowStop2)), "child2")
            throw TestException("exc from setup")
          }

          targetBehavior(parentProbe.ref)
        }
      }
        .onFailure[RuntimeException](strategy)

      EventFilter[TestException](occurrences = 1).intercept {
        val ref = spawn(behv)
        slowStop1.countDown()
        child1Probe.expectMessage(GotSignal(PostStop))
        throwFromSetup.set(false)
        slowStop2.countDown()
        child2Probe.expectMessage(GotSignal(PostStop))

        ref ! GetState
        parentProbe.expectMessageType[State].children.keySet should ===(Set("child1"))
      }
    }

    "stop children when restart (with limit) from exception in later setup" in {
      testStopChildrenWhenExceptionFromLaterSetup(SupervisorStrategy.restart.withLimit(10, 1.second))
    }

    "stop children when backoff from exception in later setup" in {
      testStopChildrenWhenExceptionFromLaterSetup(SupervisorStrategy.restartWithBackoff(10.millis, 10.millis, 0))
    }

    def testStopChildrenWhenExceptionFromLaterSetup(strategy: SupervisorStrategy): Unit = {
      val parentProbe = TestProbe[Event]("evt")
      val child1Probe = TestProbe[Event]("childEvt")
      val child2Probe = TestProbe[Event]("childEvt")
      val slowStop1 = new CountDownLatch(1)
      val slowStop2 = new CountDownLatch(1)
      val throwFromSetup = new AtomicBoolean(false)
      val behv = Behaviors.supervise {
        Behaviors.setup[Command] { ctx ⇒
          ctx.spawn(targetBehavior(child1Probe.ref, slowStop = Some(slowStop1)), "child1")
          if (throwFromSetup.get()) {
            // note that this second child waiting on slowStop2 will prevent a restart loop that could exhaust the
            // limit before throwFromSetup is set back to false
            ctx.spawn(targetBehavior(child2Probe.ref, slowStop = Some(slowStop2)), "child2")
            throw TestException("exc from setup")
          }

          targetBehavior(parentProbe.ref)
        }
      }
        .onFailure[RuntimeException](strategy)

      val ref = spawn(behv)

      ref ! GetState
      parentProbe.expectMessageType[State].children.keySet should ===(Set("child1"))

      throwFromSetup.set(true)

      EventFilter[Exc1](occurrences = 1).intercept {
        ref ! Throw(new Exc1)
        parentProbe.expectMessage(GotSignal(PreRestart))
      }

      EventFilter[TestException](occurrences = 1).intercept {
        slowStop1.countDown()
        child1Probe.expectMessage(GotSignal(PostStop))
        child1Probe.expectMessage(GotSignal(PostStop))
        throwFromSetup.set(false)
        slowStop2.countDown()
        child2Probe.expectMessage(GotSignal(PostStop))
      }

      ref ! GetState
      parentProbe.expectMessageType[State].children.keySet should ===(Set("child1"))
    }

    "resume when handled exception" in {
      val probe = TestProbe[Event]("evt")
      val behv = supervise(targetBehavior(probe.ref)).onFailure[Exc1](SupervisorStrategy.resume)
      val ref = spawn(behv)
      ref ! IncrementState
      ref ! GetState
      probe.expectMessage(State(1, Map.empty))

      EventFilter[Exc2](occurrences = 1).intercept {
        ref ! Throw(new Exc2)
        ref ! GetState
        probe.expectMessage(State(1, Map.empty))
      }
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
      probe.expectMessage(State(1, Map.empty))

      // resume
      EventFilter[Exc2](occurrences = 1).intercept {
        ref ! Throw(new Exc2)
        probe.expectNoMessage()
        ref ! GetState
        probe.expectMessage(State(1, Map.empty))
      }

      // restart
      EventFilter[Exc3](occurrences = 1).intercept {
        ref ! Throw(new Exc3)
        probe.expectMessage(GotSignal(PreRestart))
        ref ! GetState
        probe.expectMessage(State(0, Map.empty))
      }

      // stop
      EventFilter[Exc1](occurrences = 1).intercept {
        ref ! Throw(new Exc1)
        probe.expectMessage(GotSignal(PostStop))
      }
    }

    "publish dropped messages while backing off and stash is full" in {
      val probe = TestProbe[Event]("evt")
      val startedProbe = TestProbe[Event]("started")
      val minBackoff = 1.seconds
      val strategy = SupervisorStrategy
        .restartWithBackoff(minBackoff, minBackoff, 0.0).withStashCapacity(2)
      val behv = Behaviors.supervise(Behaviors.setup[Command] { _ ⇒
        startedProbe.ref ! Started
        targetBehavior(probe.ref)
      }).onFailure[Exception](strategy)

      val droppedMessagesProbe = TestProbe[Dropped]()
      system.toUntyped.eventStream.subscribe(droppedMessagesProbe.ref.toUntyped, classOf[Dropped])
      val ref = spawn(behv)
      EventFilter[Exc1](occurrences = 1).intercept {
        startedProbe.expectMessage(Started)
        ref ! Throw(new Exc1)
        probe.expectMessage(GotSignal(PreRestart))
      }
      ref ! Ping(1)
      ref ! Ping(2)
      ref ! Ping(3)
      ref ! Ping(4)
      probe.expectMessage(Pong(1))
      probe.expectMessage(Pong(2))
      droppedMessagesProbe.expectMessage(Dropped(Ping(3), ref))
      droppedMessagesProbe.expectMessage(Dropped(Ping(4), ref))
    }

    "restart after exponential backoff" in {
      val probe = TestProbe[Event]("evt")
      val startedProbe = TestProbe[Event]("started")
      val minBackoff = 1.seconds
      val strategy = SupervisorStrategy
        .restartWithBackoff(minBackoff, 10.seconds, 0.0)
        .withResetBackoffAfter(10.seconds)
        .withStashCapacity(0)
      val behv = Behaviors.supervise(Behaviors.setup[Command] { _ ⇒
        startedProbe.ref ! Started
        targetBehavior(probe.ref)
      }).onFailure[Exception](strategy)
      val ref = spawn(behv)

      EventFilter[Exc1](occurrences = 1).intercept {
        startedProbe.expectMessage(Started)
        ref ! IncrementState
        ref ! Throw(new Exc1)
        probe.expectMessage(GotSignal(PreRestart))
        ref ! Ping(1) // dropped due to backoff, no stashing
      }

      startedProbe.expectNoMessage(minBackoff - 100.millis)
      probe.expectNoMessage(minBackoff + 100.millis)
      startedProbe.expectMessage(Started)
      ref ! GetState
      probe.expectMessage(State(0, Map.empty))

      // one more time
      EventFilter[Exc1](occurrences = 1).intercept {
        ref ! IncrementState
        ref ! Throw(new Exc1)
        probe.expectMessage(GotSignal(PreRestart))
        ref ! Ping(2) // dropped due to backoff, no stashing
      }

      startedProbe.expectNoMessage((minBackoff * 2) - 100.millis)
      probe.expectNoMessage((minBackoff * 2) + 100.millis)
      startedProbe.expectMessage(Started)
      ref ! GetState
      probe.expectMessage(State(0, Map.empty))
    }

    "fail if reaching maximum number of restarts with backoff" in {
      val probe = TestProbe[Event]("evt")
      val startedProbe = TestProbe[Event]("started")
      val minBackoff = 200.millis
      val strategy = SupervisorStrategy
        .restartWithBackoff(minBackoff, 10.seconds, 0.0)
        .withMaxRestarts(2)
        .withResetBackoffAfter(10.seconds)

      val alreadyStarted = new AtomicBoolean(false)
      val behv = Behaviors.supervise(Behaviors.setup[Command] { _ ⇒
        if (alreadyStarted.get()) throw TestException("failure to restart")
        alreadyStarted.set(true)
        startedProbe.ref ! Started

        Behaviors.receiveMessagePartial {
          case Throw(boom) ⇒ throw boom
        }
      }).onFailure[Exception](strategy)
      val ref = spawn(behv)

      EventFilter[Exc1](occurrences = 1).intercept {
        EventFilter[TestException](occurrences = 2).intercept {
          startedProbe.expectMessage(Started)
          ref ! Throw(new Exc1)
          probe.expectTerminated(ref, 3.seconds)
        }
      }
    }

    "reset exponential backoff count after reset timeout" in {
      val probe = TestProbe[Event]("evt")
      val minBackoff = 1.seconds
      val strategy = SupervisorStrategy.restartWithBackoff(minBackoff, 10.seconds, 0.0)
        .withResetBackoffAfter(100.millis)
        .withStashCapacity(0)
      val behv = supervise(targetBehavior(probe.ref)).onFailure[Exc1](strategy)
      val ref = spawn(behv)

      EventFilter[Exc1](occurrences = 1).intercept {
        ref ! IncrementState
        ref ! Throw(new Exc1)
        probe.expectMessage(GotSignal(PreRestart))
        ref ! Ping(1) // dropped due to backoff, no stash
      }

      probe.expectNoMessage(minBackoff + 100.millis.dilated)
      ref ! GetState
      probe.expectMessage(State(0, Map.empty))

      // one more time after the reset timeout
      EventFilter[Exc1](occurrences = 1).intercept {
        probe.expectNoMessage(strategy.resetBackoffAfter + 100.millis.dilated)
        ref ! IncrementState
        ref ! Throw(new Exc1)
        probe.expectMessage(GotSignal(PreRestart))
        ref ! Ping(2) // dropped due to backoff
      }

      // backoff was reset, so restarted after the minBackoff
      probe.expectNoMessage(minBackoff + 100.millis.dilated)
      ref ! GetState
      probe.expectMessage(State(0, Map.empty))
    }

    "create underlying deferred behavior immediately" in {
      val probe = TestProbe[Event]("evt")
      val behv = supervise(setup[Command] { _ ⇒
        probe.ref ! Started
        targetBehavior(probe.ref)
      }).onFailure[Exception](SupervisorStrategy.restart)
      probe.expectNoMessage() // not yet
      spawn(behv)
      // it's supposed to be created immediately (not waiting for first message)
      probe.expectMessage(Started)
    }

    "fail instead of restart when deferred factory throws" in new FailingDeferredTestSetup(
      failCount = 1, strategy = SupervisorStrategy.restart) {

      EventFilter[ActorInitializationException](occurrences = 1).intercept {
        spawn(behv)
      }
    }

    "fail to restart when deferred factory throws unhandled" in new FailingUnhandledTestSetup(
      strategy = SupervisorStrategy.restart) {

      EventFilter[ActorInitializationException](occurrences = 1).intercept {
        spawn(behv)
      }
    }

    "fail to resume when deferred factory throws" in new FailingDeferredTestSetup(
      failCount = 1,
      strategy = SupervisorStrategy.resume
    ) {
      EventFilter[TestException](occurrences = 1).intercept {
        EventFilter[ActorInitializationException](occurrences = 1).intercept {
          spawn(behv)
        }
      }
    }

    "restart with exponential backoff when deferred factory throws" in new FailingDeferredTestSetup(
      failCount = 1,
      strategy = SupervisorStrategy.restartWithBackoff(minBackoff = 100.millis.dilated, maxBackoff = 1.second, 0)
    ) {

      EventFilter[TestException](occurrences = 1).intercept {
        spawn(behv)

        probe.expectMessage(StartFailed)
        // restarted after a delay when first start failed
        probe.expectNoMessage(100.millis)
        probe.expectMessage(Started)
      }
    }

    "fail instead of restart with exponential backoff when deferred factory throws unhandled" in new FailingUnhandledTestSetup(
      strategy = SupervisorStrategy.restartWithBackoff(minBackoff = 100.millis.dilated, maxBackoff = 1.second, 0)) {

      EventFilter[ActorInitializationException](occurrences = 1).intercept {
        spawn(behv)
        probe.expectMessage(StartFailed)
      }
    }

    "restart.withLimit when deferred factory throws" in new FailingDeferredTestSetup(
      failCount = 1,
      strategy = SupervisorStrategy.restart.withLimit(3, 1.second)
    ) {

      EventFilter[TestException](occurrences = 1).intercept {
        spawn(behv)

        probe.expectMessage(StartFailed)
        probe.expectMessage(Started)
      }
    }

    "fail after more than limit in restart.withLimit when deferred factory throws" in new FailingDeferredTestSetup(
      failCount = 20,
      strategy = SupervisorStrategy.restart.withLimit(2, 1.second)
    ) {

      EventFilter[ActorInitializationException](occurrences = 1).intercept {
        EventFilter[TestException](occurrences = 2).intercept {
          spawn(behv)

          // first one from initial setup
          probe.expectMessage(StartFailed)
          // and then restarted 2 times before it gave up
          probe.expectMessage(StartFailed)
          probe.expectMessage(StartFailed)
          probe.expectNoMessage(100.millis)
        }
      }
    }

    "fail instead of restart with limit when deferred factory throws unhandled" in new FailingUnhandledTestSetup(
      strategy = SupervisorStrategy.restart.withLimit(3, 1.second)) {

      EventFilter[ActorInitializationException](occurrences = 1).intercept {
        spawn(behv)
        probe.expectMessage(StartFailed)
      }
    }

    "fail when exception from AbstractBehavior constructor" in new FailingConstructorTestSetup(failCount = 1) {
      val probe = TestProbe[Event]("evt")
      val behv = supervise(setup[Command](_ ⇒ new FailingConstructor(probe.ref)))
        .onFailure[Exception](SupervisorStrategy.restart)

      EventFilter[ActorInitializationException](occurrences = 1).intercept {
        spawn(behv)
        probe.expectMessage(Started) // first one before failure
      }
    }

    "work with nested supervisions and defers" in {
      val strategy = SupervisorStrategy.restart.withLimit(3, 1.second)
      val probe = TestProbe[AnyRef]("p")
      val beh = supervise[String](setup(_ ⇒
        supervise[String](setup { _ ⇒
          probe.ref ! Started
          Behaviors.empty[String]
        }).onFailure[RuntimeException](strategy)
      )).onFailure[Exception](strategy)

      spawn(beh)
      probe.expectMessage(Started)
    }

    "replace supervision when new returned behavior catches same exception" in {
      val probe = TestProbe[AnyRef]("probeMcProbeFace")
      val behv = supervise[String](Behaviors.receiveMessage {
        case "boom" ⇒ throw TestException("boom indeed")
        case "switch" ⇒
          supervise[String](
            supervise[String](
              supervise[String](
                supervise[String](
                  supervise[String](
                    Behaviors.receiveMessage {
                      case "boom" ⇒ throw TestException("boom indeed")
                      case "ping" ⇒
                        probe.ref ! "pong"
                        Behaviors.same
                      case "give me stacktrace" ⇒
                        probe.ref ! new RuntimeException().getStackTrace.toVector
                        Behaviors.stopped
                    }).onFailure[RuntimeException](SupervisorStrategy.resume)
                ).onFailure[RuntimeException](SupervisorStrategy.restartWithBackoff(1.second, 10.seconds, 23D))
              ).onFailure[RuntimeException](SupervisorStrategy.restart.withLimit(23, 10.seconds))
            ).onFailure[IllegalArgumentException](SupervisorStrategy.restart)
          ).onFailure[RuntimeException](SupervisorStrategy.restart)
      }).onFailure[RuntimeException](SupervisorStrategy.stop)

      val actor = spawn(behv)
      actor ! "switch"
      actor ! "ping"
      probe.expectMessage("pong")

      EventFilter[RuntimeException](occurrences = 1).intercept {
        // Should be supervised as resume
        actor ! "boom"
      }

      actor ! "give me stacktrace"
      val stacktrace = probe.expectMessageType[Vector[StackTraceElement]]
      // InterceptorImpl receive is used for every supervision instance, only wrapped in one supervisor for RuntimeException
      // and then the IllegalArgument one is kept since it has a different throwable
      stacktrace.count(_.toString.startsWith("akka.actor.typed.internal.InterceptorImpl.receive")) should ===(2)
    }

    "replace supervision when new returned behavior catches same exception nested in other behaviors" in {
      val probe = TestProbe[AnyRef]("probeMcProbeFace")

      // irrelevant for test case but needed to use intercept in the pyramid of doom below
      val whateverInterceptor = new BehaviorInterceptor[String, String] {
        // identity intercept
        override def aroundReceive(context: TypedActorContext[String], message: String, target: ReceiveTarget[String]): Behavior[String] =
          target(context, message)

        override def aroundSignal(context: TypedActorContext[String], signal: Signal, target: SignalTarget[String]): Behavior[String] =
          target(context, signal)
      }

      val behv = supervise[String](Behaviors.receiveMessage {
        case "boom" ⇒ throw TestException("boom indeed")
        case "switch" ⇒
          supervise[String](
            setup(_ ⇒
              supervise[String](
                Behaviors.intercept(whateverInterceptor)(
                  supervise[String](
                    Behaviors.receiveMessage {
                      case "boom" ⇒ throw TestException("boom indeed")
                      case "ping" ⇒
                        probe.ref ! "pong"
                        Behaviors.same
                      case "give me stacktrace" ⇒
                        probe.ref ! new RuntimeException().getStackTrace.toVector
                        Behaviors.stopped
                    }).onFailure[RuntimeException](SupervisorStrategy.resume)
                )
              ).onFailure[IllegalArgumentException](SupervisorStrategy.restart.withLimit(23, 10.seconds))
            )
          ).onFailure[RuntimeException](SupervisorStrategy.restart)
      }).onFailure[RuntimeException](SupervisorStrategy.stop)

      val actor = spawn(behv)
      actor ! "switch"
      actor ! "ping"
      probe.expectMessage("pong")

      EventFilter[RuntimeException](occurrences = 1).intercept {
        // Should be supervised as resume
        actor ! "boom"
      }

      actor ! "give me stacktrace"
      val stacktrace = probe.expectMessageType[Vector[StackTraceElement]]
      stacktrace.count(_.toString.contains("Supervisor.aroundReceive")) should ===(2)
    }

    "replace backoff supervision duplicate when behavior is created in a setup" in {
      val probe = TestProbe[AnyRef]("probeMcProbeFace")
      val restartCount = new AtomicInteger(0)
      val behv = supervise[String](
        Behaviors.setup { _ ⇒

          // a bit superficial, but just to be complete
          if (restartCount.incrementAndGet() == 1) {
            probe.ref ! "started 1"
            Behaviors.receiveMessage {
              case "boom" ⇒
                probe.ref ! "crashing 1"
                throw TestException("boom indeed")
              case "ping" ⇒
                probe.ref ! "pong 1"
                Behaviors.same
            }
          } else {
            probe.ref ! "started 2"
            Behaviors.supervise[String](
              Behaviors.receiveMessage {
                case "boom" ⇒
                  probe.ref ! "crashing 2"
                  throw TestException("boom indeed")
                case "ping" ⇒
                  probe.ref ! "pong 2"
                  Behaviors.same
              }
            ).onFailure[TestException](SupervisorStrategy.resume)
          }
        }
      ).onFailure(SupervisorStrategy.restartWithBackoff(100.millis, 1.second, 0))

      val ref = spawn(behv)
      probe.expectMessage("started 1")
      ref ! "ping"
      probe.expectMessage("pong 1")
      EventFilter[TestException](occurrences = 1).intercept {
        ref ! "boom"
        probe.expectMessage("crashing 1")
        ref ! "ping"
        probe.expectNoMessage(100.millis)
      }
      probe.expectMessage("started 2")
      probe.expectMessage("pong 2") // from "ping" that was stashed
      ref ! "ping"
      probe.expectMessage("pong 2")
      EventFilter[TestException](occurrences = 1).intercept {
        ref ! "boom" // now we should have replaced supervision with the resuming one
        probe.expectMessage("crashing 2")
      }
      ref ! "ping"
      probe.expectMessage("pong 2")
    }

    "be able to recover from a DeathPactException" in {
      val probe = TestProbe[AnyRef]()
      val actor = spawn(Behaviors.supervise(Behaviors.setup[String] { context ⇒
        val child = context.spawnAnonymous(Behaviors.receive[String] { (context, message) ⇒
          message match {
            case "boom" ⇒
              probe.ref ! context.self
              Behaviors.stopped
          }
        })
        context.watch(child)

        Behaviors.receiveMessage {
          case "boom" ⇒
            child ! "boom"
            Behaviors.same
          case "ping" ⇒
            probe.ref ! "pong"
            Behaviors.same
        }
      }).onFailure[DeathPactException](SupervisorStrategy.restart))

      EventFilter[DeathPactException](occurrences = 1).intercept {
        actor ! "boom"
        val child = probe.expectMessageType[ActorRef[_]]
        probe.expectTerminated(child, 3.seconds)
      }
      actor ! "ping"
      probe.expectMessage("pong")
    }

  }

  val allStrategies = Seq(
    SupervisorStrategy.stop,
    SupervisorStrategy.restart,
    SupervisorStrategy.resume,
    SupervisorStrategy.restartWithBackoff(1.millis, 100.millis, 2D),
    SupervisorStrategy.restart.withLimit(1, 100.millis)
  )

  allStrategies.foreach { strategy ⇒

    s"Supervision with the strategy $strategy" should {

      "that is initially stopped should be stopped" in {
        val actor = spawn(
          Behaviors.supervise(Behaviors.stopped[Command])
            .onFailure(strategy)
        )
        createTestProbe().expectTerminated(actor, 3.second)
      }

      "that is stopped after setup should be stopped" in {
        val actor = spawn(
          Behaviors.supervise[Command](
            Behaviors.setup(_ ⇒
              Behaviors.stopped)
          ).onFailure(strategy)
        )
        createTestProbe().expectTerminated(actor, 3.second)
      }

      // this test doesn't make sense for Resume since there will be no second setup
      if (!strategy.isInstanceOf[Resume]) {
        "that is stopped after restart should be stopped" in {
          val stopInSetup = new AtomicBoolean(false)
          val actor = spawn(
            Behaviors.supervise[String](
              Behaviors.setup { _ ⇒
                if (stopInSetup.get()) {
                  Behaviors.stopped
                } else {
                  stopInSetup.set(true)
                  Behaviors.receiveMessage {
                    case "boom" ⇒ throw TestException("boom")
                  }
                }
              }).onFailure[TestException](strategy)
          )

          EventFilter[TestException](occurrences = 1).intercept {
            actor ! "boom"
          }
          createTestProbe().expectTerminated(actor, 3.second)
        }
      }
    }
  }
}
