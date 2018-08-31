/**
 * Copyright (C) 2017-2018 Lightbend Inc. <https://www.lightbend.com>
 */

package akka.actor.typed

import java.io.IOException
import java.util.concurrent.atomic.{ AtomicBoolean, AtomicInteger }

import akka.actor.{ ActorInitializationException, typed }
import akka.actor.typed.scaladsl.{ Behaviors, MutableBehavior }
import akka.actor.typed.scaladsl.Behaviors._
import akka.testkit.{ ErrorFilter, EventFilter }
import akka.actor.testkit.typed.scaladsl._
import akka.actor.testkit.typed._
import com.typesafe.config.ConfigFactory
import org.scalatest.{ Matchers, WordSpec }

import scala.concurrent.duration._
import scala.util.control.NoStackTrace
import scala.concurrent.duration._
import akka.actor.typed.SupervisorStrategy.Resume

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

    "replace supervision when new returned behavior catches same exception" in {
      val probe = TestProbe[AnyRef]("probeMcProbeFace")
      val behv = supervise[String](Behaviors.receiveMessage {
        case "boom" ⇒ throw TE("boom indeed")
        case "switch" ⇒
          supervise[String](
            supervise[String](
              supervise[String](
                supervise[String](
                  supervise[String](
                    Behaviors.receiveMessage {
                      case "boom" ⇒ throw TE("boom indeed")
                      case "ping" ⇒
                        probe.ref ! "pong"
                        Behaviors.same
                      case "give me stacktrace" ⇒
                        probe.ref ! new RuntimeException().getStackTrace.toVector
                        Behaviors.stopped
                    }).onFailure[RuntimeException](SupervisorStrategy.resume)
                ).onFailure[RuntimeException](SupervisorStrategy.restartWithBackoff(1.second, 10.seconds, 23D))
              ).onFailure[RuntimeException](SupervisorStrategy.restartWithLimit(23, 10.seconds))
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
      // supervisor receive is used for every supervision instance, only wrapped in one supervisor for RuntimeException
      // and then the IllegalArgument one is kept since it has a different throwable
      stacktrace.count(_.toString.startsWith("akka.actor.typed.internal.Supervisor.receive")) should ===(2)
    }

    "replace supervision when new returned behavior catches same exception nested in other behaviors" in {
      val probe = TestProbe[AnyRef]("probeMcProbeFace")

      // irrelevant for test case but needed to use intercept in the pyramid of doom below
      val whateverInterceptor = new BehaviorInterceptor[String, String] {
        // identity intercept
        override def aroundReceive(ctx: ActorContext[String], msg: String, target: ReceiveTarget[String]): Behavior[String] =
          target(msg)

        override def aroundSignal(ctx: ActorContext[String], signal: Signal, target: SignalTarget[String]): Behavior[String] =
          target(signal)
      }

      val behv = supervise[String](Behaviors.receiveMessage {
        case "boom" ⇒ throw TE("boom indeed")
        case "switch" ⇒
          supervise[String](
            setup(ctx ⇒
              supervise[String](
                Behaviors.intercept(whateverInterceptor)(
                  supervise[String](
                    Behaviors.receiveMessage {
                      case "boom" ⇒ throw TE("boom indeed")
                      case "ping" ⇒
                        probe.ref ! "pong"
                        Behaviors.same
                      case "give me stacktrace" ⇒
                        probe.ref ! new RuntimeException().getStackTrace.toVector
                        Behaviors.stopped
                    }).onFailure[RuntimeException](SupervisorStrategy.resume)
                ) // whatever
              ).onFailure[IllegalArgumentException](SupervisorStrategy.restartWithLimit(23, 10.seconds))
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
      // supervisor receive is used for every supervision instance, only wrapped in one supervisor for RuntimeException
      // and then the IllegalArgument one is kept since it has a different throwable
      stacktrace.count(_.toString.startsWith("akka.actor.typed.internal.Supervisor.receive")) should ===(2)
    }

    "replace backoff supervision duplicate when behavior is created in a setup" in {
      val probe = TestProbe[AnyRef]("probeMcProbeFace")
      val restartCount = new AtomicInteger(0)
      val behv = supervise[String](
        Behaviors.setup { ctx ⇒

          // a bit superficial, but just to be complete
          if (restartCount.incrementAndGet() == 1) {
            probe.ref ! "started 1"
            Behaviors.receiveMessage {
              case "boom" ⇒
                probe.ref ! "crashing 1"
                throw TE("boom indeed")
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
                  throw TE("boom indeed")
                case "ping" ⇒
                  probe.ref ! "pong 2"
                  Behaviors.same
              }
            ).onFailure[TE](SupervisorStrategy.resume)
          }
        }
      ).onFailure(SupervisorStrategy.restartWithBackoff(100.millis, 1.second, 0))

      val ref = spawn(behv)
      probe.expectMessage("started 1")
      ref ! "ping"
      probe.expectMessage("pong 1")
      EventFilter[TE](occurrences = 1).intercept {
        ref ! "boom"
        probe.expectMessage("crashing 1")
        ref ! "ping"
        probe.expectNoMessage(100.millis)
      }
      probe.expectMessage("started 2")
      ref ! "ping"
      probe.expectMessage("pong 2")
      EventFilter[TE](occurrences = 1).intercept {
        ref ! "boom" // now we should have replaced supervision with the resuming one
        probe.expectMessage("crashing 2")
      }
      ref ! "ping"
      probe.expectMessage("pong 2")
    }

    "be able to recover from a DeathPactException" in {
      val probe = TestProbe[AnyRef]()
      val actor = spawn(Behaviors.supervise(Behaviors.setup[String] { ctx ⇒
        val child = ctx.spawnAnonymous(Behaviors.receive[String] { (ctx, msg) ⇒
          msg match {
            case "boom" ⇒
              probe.ref ! ctx.self
              Behaviors.stopped
          }
        })
        ctx.watch(child)

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
    SupervisorStrategy.restartWithLimit(1, 100.millis)
  )

  allStrategies.foreach { strategy ⇒

    s"Supervision with the strategy $strategy" should {

      "that is initially stopped should be stopped" in {
        val actor = spawn(
          Behaviors.supervise(Behaviors.stopped[Command])
            .onFailure(strategy)
        )
        TestProbe().expectTerminated(actor, 3.second)
      }

      "that is stopped after setup should be stopped" in {
        val actor = spawn(
          Behaviors.supervise[Command](
            Behaviors.setup(_ ⇒
              Behaviors.stopped)
          ).onFailure(strategy)
        )
        TestProbe().expectTerminated(actor, 3.second)
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
                    case "boom" ⇒ throw TE("boom")
                  }
                }
              }).onFailure[TE](strategy)
          )

          EventFilter[TE](occurrences = 1).intercept {
            actor ! "boom"
          }
          TestProbe().expectTerminated(actor, 3.second)
        }
      }
    }
  }
}
