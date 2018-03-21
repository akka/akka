/**
 * Copyright (C) 2017-2018 Lightbend Inc. <https://www.lightbend.com>
 */

package akka.actor.typed

import java.io.IOException
import java.util.concurrent.atomic.AtomicInteger

import akka.actor.ActorInitializationException
import akka.actor.typed.scaladsl.{ Behaviors, MutableBehavior }
import akka.actor.typed.scaladsl.Behaviors._
import akka.testkit.EventFilter
import akka.testkit.typed.scaladsl._
import akka.testkit.typed._
import com.typesafe.config.ConfigFactory
import org.scalatest.{ Matchers, WordSpec }

import scala.concurrent.duration._
import scala.util.control.NoStackTrace

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
  case object StartFailed extends Event

  class Exc1(msg: String = "exc-1") extends RuntimeException(msg) with NoStackTrace
  class Exc2 extends Exc1("exc-2")
  class Exc3(msg: String = "exc-3") extends RuntimeException(msg) with NoStackTrace

  def targetBehavior(monitor: ActorRef[Event], state: State = State(0, Map.empty)): Behavior[Command] =
    receive[Command] { (ctx, cmd) ⇒
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
    } receiveSignal {
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

  def mkTestkit(behv: Behavior[Command]): BehaviorTestKit[Command] =
    BehaviorTestKit(behv)

  "A restarter (stubbed)" must {
    "receive message" in {
      val inbox = TestInbox[Event]("evt")
      val behv = supervise(targetBehavior(inbox.ref)).onFailure[Throwable](SupervisorStrategy.restart)
      val testkit = BehaviorTestKit(behv)
      testkit.run(Ping)
      inbox.receiveMessage() should ===(Pong)
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
      val strategy = SupervisorStrategy.restartWithLimit(maxNrOfRetries = 2, withinTimeRange = 1.minute)
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
      val strategy = SupervisorStrategy.restartWithLimit(maxNrOfRetries = 2, withinTimeRange)
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
      val strategy = SupervisorStrategy.restartWithLimit(maxNrOfRetries = 0, withinTimeRange = 1.minute)
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
      mkTestkit(behv)
      // it's supposed to be created immediately (not waiting for first message)
      inbox.receiveMessage() should ===(Started)
    }
  }
}

class SupervisionSpec extends ActorTestKit with TypedAkkaSpecWithShutdown {

  override def config = ConfigFactory.parseString(
    """
      akka.loggers = [akka.testkit.TestEventListener]
    """)

  import SupervisionSpec._
  private val nameCounter = Iterator.from(0)
  private def nextName(prefix: String = "a"): String = s"$prefix-${nameCounter.next()}"

  // FIXME eventfilter support in typed testkit
  import scaladsl.adapter._
  implicit val untypedSystem = system.toUntyped

  class FailingConstructorTestSetup(failCount: Int) {
    val failCounter = new AtomicInteger(0)
    class FailingConstructor(monitor: ActorRef[Event]) extends MutableBehavior[Command] {
      monitor ! Started
      if (failCounter.getAndIncrement() < failCount) {
        throw TE("simulated exc from constructor")
      }
      override def onMessage(msg: Command): Behavior[Command] = {
        monitor ! Pong
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
        throw TE(s"construction ${count} failed")
      } else {
        probe.ref ! Started
        Behaviors.empty
      }
    }).onFailure[TE](strategy)
  }

  class FailingUnhandledTestSetup(strategy: SupervisorStrategy) {
    val probe = TestProbe[AnyRef]("evt")
    def behv = supervise(setup[Command] { _ ⇒
      probe.ref ! StartFailed
      throw new TE("construction failed")
    }).onFailure[IllegalArgumentException](strategy)
  }

  "A supervised actor" must {
    "receive message" in {
      val probe = TestProbe[Event]("evt")
      val behv = Behaviors.supervise(targetBehavior(probe.ref))
        .onFailure[Throwable](SupervisorStrategy.restart)
      val ref = spawn(behv)
      ref ! Ping
      probe.expectMessage(Pong)
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

    "NOT stop children when restarting" in {
      val parentProbe = TestProbe[Event]("evt")
      val behv = Behaviors.supervise(targetBehavior(parentProbe.ref))
        .onFailure[Exc1](SupervisorStrategy.restart)
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
      // TODO document this difference compared to classic actors, and that
      //      children can be stopped if needed in PreRestart
      parentProbe.expectMessageType[State].children.keySet should contain(childName)
      childProbe.expectNoMessage()
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

    "restart after exponential backoff" in {
      val probe = TestProbe[Event]("evt")
      val startedProbe = TestProbe[Event]("started")
      val minBackoff = 1.seconds
      val strategy = SupervisorStrategy
        .restartWithBackoff(minBackoff, 10.seconds, 0.0)
        .withResetBackoffAfter(10.seconds)
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
        ref ! Ping // dropped due to backoff
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
        ref ! Ping // dropped due to backoff
      }

      startedProbe.expectNoMessage((minBackoff * 2) - 100.millis)
      probe.expectNoMessage((minBackoff * 2) + 100.millis)
      startedProbe.expectMessage(Started)
      ref ! GetState
      probe.expectMessage(State(0, Map.empty))
    }

    "reset exponential backoff count after reset timeout" in {
      val probe = TestProbe[Event]("evt")
      val minBackoff = 1.seconds
      val strategy = SupervisorStrategy.restartWithBackoff(minBackoff, 10.seconds, 0.0)
        .withResetBackoffAfter(100.millis)
      val behv = supervise(targetBehavior(probe.ref)).onFailure[Exc1](strategy)
      val ref = spawn(behv)

      EventFilter[Exc1](occurrences = 1).intercept {
        ref ! IncrementState
        ref ! Throw(new Exc1)
        probe.expectMessage(GotSignal(PreRestart))
        ref ! Ping // dropped due to backoff
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
        ref ! Ping // dropped due to backoff
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
      EventFilter[ActorInitializationException](occurrences = 1).intercept {
        spawn(behv)
      }
    }

    "restart with exponential backoff when deferred factory throws" in new FailingDeferredTestSetup(
      failCount = 1,
      strategy = SupervisorStrategy.restartWithBackoff(minBackoff = 100.millis.dilated, maxBackoff = 1.second, 0)
    ) {

      EventFilter[TE](occurrences = 1).intercept {
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

    "restartWithLimit when deferred factory throws" in new FailingDeferredTestSetup(
      failCount = 1,
      strategy = SupervisorStrategy.restartWithLimit(3, 1.second)
    ) {

      EventFilter[TE](occurrences = 1).intercept {
        spawn(behv)

        probe.expectMessage(StartFailed)
        probe.expectMessage(Started)
      }
    }

    "fail after more than limit in restartWithLimit when deferred factory throws" in new FailingDeferredTestSetup(
      failCount = 3,
      strategy = SupervisorStrategy.restartWithLimit(2, 1.second)
    ) {

      EventFilter[ActorInitializationException](occurrences = 1).intercept {
        EventFilter[TE](occurrences = 2).intercept {
          spawn(behv)

          // restarted 2 times before it gave up
          probe.expectMessage(StartFailed)
          probe.expectMessage(StartFailed)
          probe.expectNoMessage(100.millis)
        }
      }
    }

    "fail instead of restart with limit when deferred factory throws unhandled" in new FailingUnhandledTestSetup(
      strategy = SupervisorStrategy.restartWithLimit(3, 1.second)) {

      EventFilter[ActorInitializationException](occurrences = 1).intercept {
        spawn(behv)
        probe.expectMessage(StartFailed)
      }
    }

    "fail when exception from MutableBehavior constructor" in new FailingConstructorTestSetup(failCount = 1) {
      val probe = TestProbe[Event]("evt")
      val behv = supervise(setup[Command](_ ⇒ new FailingConstructor(probe.ref)))
        .onFailure[Exception](SupervisorStrategy.restart)

      EventFilter[ActorInitializationException](occurrences = 1).intercept {
        val ref = spawn(behv)
        probe.expectMessage(Started) // first one before failure
      }
    }

    "work with nested supervisions and defers" in {
      val strategy = SupervisorStrategy.restartWithLimit(3, 1.second)
      val probe = TestProbe[AnyRef]("p")
      val beh = supervise[String](setup(ctx ⇒
        supervise[String](setup { ctx ⇒
          probe.ref ! Started
          scaladsl.Behaviors.empty[String]
        }).onFailure[RuntimeException](strategy)
      )).onFailure[Exception](strategy)

      spawn(beh)
      probe.expectMessage(Started)
    }

  }
}
