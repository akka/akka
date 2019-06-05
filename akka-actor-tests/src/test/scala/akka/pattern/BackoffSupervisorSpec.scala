/*
 * Copyright (C) 2015-2019 Lightbend Inc. <https://www.lightbend.com>
 */

package akka.pattern

import akka.actor._
import akka.testkit._
import com.github.ghik.silencer.silent
import org.scalatest.concurrent.Eventually
import org.scalatest.prop.TableDrivenPropertyChecks._

import scala.concurrent.duration._
import scala.util.control.NoStackTrace

object BackoffSupervisorSpec {

  class TestException extends RuntimeException with NoStackTrace

  object Child {
    def props(probe: ActorRef): Props =
      Props(new Child(probe))
  }

  class Child(probe: ActorRef) extends Actor {
    def receive = {
      case "boom" => throw new TestException
      case msg    => probe ! msg
    }
  }

  object ManualChild {
    def props(probe: ActorRef): Props =
      Props(new ManualChild(probe))
  }

  class ManualChild(probe: ActorRef) extends Actor {
    def receive = {
      case "boom" => throw new TestException
      case msg =>
        probe ! msg
        context.parent ! BackoffSupervisor.Reset
    }
  }
}

class BackoffSupervisorSpec extends AkkaSpec with ImplicitSender with Eventually {
  import BackoffSupervisorSpec._

  @silent
  def onStopOptions(props: Props = Child.props(testActor), maxNrOfRetries: Int = -1) =
    Backoff.onStop(props, "c1", 100.millis, 3.seconds, 0.2, maxNrOfRetries)
  @silent
  def onFailureOptions(props: Props = Child.props(testActor), maxNrOfRetries: Int = -1) =
    Backoff.onFailure(props, "c1", 100.millis, 3.seconds, 0.2, maxNrOfRetries)
  @silent
  def create(options: BackoffOptions) = system.actorOf(BackoffSupervisor.props(options))

  "BackoffSupervisor" must {
    "start child again when it stops when using `Backoff.onStop`" in {
      val supervisor = create(onStopOptions())
      supervisor ! BackoffSupervisor.GetCurrentChild
      val c1 = expectMsgType[BackoffSupervisor.CurrentChild].ref.get
      watch(c1)
      c1 ! PoisonPill
      expectTerminated(c1)
      awaitAssert {
        supervisor ! BackoffSupervisor.GetCurrentChild
        // new instance
        expectMsgType[BackoffSupervisor.CurrentChild].ref.get should !==(c1)
      }
    }

    "forward messages to the child" in {
      def assertForward(supervisor: ActorRef) = {
        supervisor ! "hello"
        expectMsg("hello")
      }
      assertForward(create(onStopOptions()))
      assertForward(create(onFailureOptions()))
    }

    "support custom supervision strategy" in {
      def assertCustomStrategy(supervisor: ActorRef) = {
        supervisor ! BackoffSupervisor.GetCurrentChild
        val c1 = expectMsgType[BackoffSupervisor.CurrentChild].ref.get
        watch(c1)
        c1 ! "boom"
        expectTerminated(c1)
        awaitAssert {
          supervisor ! BackoffSupervisor.GetCurrentChild
          // new instance
          expectMsgType[BackoffSupervisor.CurrentChild].ref.get should !==(c1)
        }
      }
      filterException[TestException] {
        val stoppingStrategy = OneForOneStrategy() {
          case _: TestException => SupervisorStrategy.Stop
        }
        val restartingStrategy = OneForOneStrategy() {
          case _: TestException => SupervisorStrategy.Restart
        }

        assertCustomStrategy(create(onStopOptions().withSupervisorStrategy(stoppingStrategy)))

        assertCustomStrategy(create(onFailureOptions().withSupervisorStrategy(restartingStrategy)))
      }
    }

    "support default stopping strategy when using `Backoff.onStop`" in {
      filterException[TestException] {
        val supervisor = create(onStopOptions().withDefaultStoppingStrategy)
        supervisor ! BackoffSupervisor.GetCurrentChild
        val c1 = expectMsgType[BackoffSupervisor.CurrentChild].ref.get
        watch(c1)
        supervisor ! BackoffSupervisor.GetRestartCount
        expectMsg(BackoffSupervisor.RestartCount(0))

        c1 ! "boom"
        expectTerminated(c1)
        awaitAssert {
          supervisor ! BackoffSupervisor.GetCurrentChild
          // new instance
          expectMsgType[BackoffSupervisor.CurrentChild].ref.get should !==(c1)
        }
        supervisor ! BackoffSupervisor.GetRestartCount
        expectMsg(BackoffSupervisor.RestartCount(1))

      }
    }

    "support manual reset" in {
      filterException[TestException] {
        def assertManualReset(supervisor: ActorRef) = {
          supervisor ! BackoffSupervisor.GetCurrentChild
          val c1 = expectMsgType[BackoffSupervisor.CurrentChild].ref.get
          watch(c1)
          c1 ! "boom"
          expectTerminated(c1)

          awaitAssert {
            supervisor ! BackoffSupervisor.GetRestartCount
            expectMsg(BackoffSupervisor.RestartCount(1))
          }

          awaitAssert {
            supervisor ! BackoffSupervisor.GetCurrentChild
            // new instance
            expectMsgType[BackoffSupervisor.CurrentChild].ref.get should !==(c1)
          }

          supervisor ! "hello"
          expectMsg("hello")

          // making sure the Reset is handled by supervisor
          supervisor ! "hello"
          expectMsg("hello")

          supervisor ! BackoffSupervisor.GetRestartCount
          expectMsg(BackoffSupervisor.RestartCount(0))
        }

        val stoppingStrategy = OneForOneStrategy() {
          case _: TestException => SupervisorStrategy.Stop
        }
        val restartingStrategy = OneForOneStrategy() {
          case _: TestException => SupervisorStrategy.Restart
        }

        assertManualReset(
          create(onStopOptions(ManualChild.props(testActor)).withManualReset.withSupervisorStrategy(stoppingStrategy)))

        assertManualReset(
          create(
            onFailureOptions(ManualChild.props(testActor)).withManualReset.withSupervisorStrategy(restartingStrategy)))
      }
    }

    "reply to sender if replyWhileStopped is specified" in {
      filterException[TestException] {
        @silent
        val supervisor = create(
          Backoff
            .onFailure(Child.props(testActor), "c1", 100.seconds, 300.seconds, 0.2, maxNrOfRetries = -1)
            .withReplyWhileStopped("child was stopped"))
        supervisor ! BackoffSupervisor.GetCurrentChild
        val c1 = expectMsgType[BackoffSupervisor.CurrentChild].ref.get
        watch(c1)
        supervisor ! BackoffSupervisor.GetRestartCount
        expectMsg(BackoffSupervisor.RestartCount(0))

        c1 ! "boom"
        expectTerminated(c1)

        awaitAssert {
          supervisor ! BackoffSupervisor.GetRestartCount
          expectMsg(BackoffSupervisor.RestartCount(1))
        }

        supervisor ! "boom"
        expectMsg("child was stopped")
      }
    }

    "not reply to sender if replyWhileStopped is NOT specified" in {
      filterException[TestException] {
        @silent
        val supervisor =
          create(Backoff.onFailure(Child.props(testActor), "c1", 100.seconds, 300.seconds, 0.2, maxNrOfRetries = -1))
        supervisor ! BackoffSupervisor.GetCurrentChild
        val c1 = expectMsgType[BackoffSupervisor.CurrentChild].ref.get
        watch(c1)
        supervisor ! BackoffSupervisor.GetRestartCount
        expectMsg(BackoffSupervisor.RestartCount(0))

        c1 ! "boom"
        expectTerminated(c1)

        awaitAssert {
          supervisor ! BackoffSupervisor.GetRestartCount
          expectMsg(BackoffSupervisor.RestartCount(1))
        }

        supervisor ! "boom" //this will be sent to deadLetters
        expectNoMessage(500.milliseconds)
      }
    }

    "correctly calculate the delay" in {
      val delayTable =
        Table(
          ("restartCount", "minBackoff", "maxBackoff", "randomFactor", "expectedResult"),
          (0, 0.minutes, 0.minutes, 0d, 0.minutes),
          (0, 5.minutes, 7.minutes, 0d, 5.minutes),
          (2, 5.seconds, 7.seconds, 0d, 7.seconds),
          (2, 5.seconds, 7.days, 0d, 20.seconds),
          (29, 5.minutes, 10.minutes, 0d, 10.minutes),
          (29, 10000.days, 10000.days, 0d, 10000.days),
          (Int.MaxValue, 10000.days, 10000.days, 0d, 10000.days))
      forAll(delayTable) {
        (
            restartCount: Int,
            minBackoff: FiniteDuration,
            maxBackoff: FiniteDuration,
            randomFactor: Double,
            expectedResult: FiniteDuration) =>
          val calculatedValue = BackoffSupervisor.calculateDelay(restartCount, minBackoff, maxBackoff, randomFactor)
          assert(calculatedValue === expectedResult)
      }
    }

    "stop restarting the child after reaching maxNrOfRetries limit (Backoff.onStop)" in {
      val supervisor = create(onStopOptions(maxNrOfRetries = 2))
      def waitForChild: Option[ActorRef] = {
        eventually(timeout(1.second), interval(50.millis)) {
          supervisor ! BackoffSupervisor.GetCurrentChild
          val c = expectMsgType[BackoffSupervisor.CurrentChild].ref
          c.isDefined shouldBe true
        }

        supervisor ! BackoffSupervisor.GetCurrentChild
        expectMsgType[BackoffSupervisor.CurrentChild].ref
      }

      watch(supervisor)

      supervisor ! BackoffSupervisor.GetRestartCount
      expectMsg(BackoffSupervisor.RestartCount(0))

      supervisor ! BackoffSupervisor.GetCurrentChild
      val c1 = expectMsgType[BackoffSupervisor.CurrentChild].ref.get
      watch(c1)
      c1 ! PoisonPill
      expectTerminated(c1)

      supervisor ! BackoffSupervisor.GetRestartCount
      expectMsg(BackoffSupervisor.RestartCount(1))

      val c2 = waitForChild.get
      awaitAssert(c2 should !==(c1))
      watch(c2)
      c2 ! PoisonPill
      expectTerminated(c2)

      supervisor ! BackoffSupervisor.GetRestartCount
      expectMsg(BackoffSupervisor.RestartCount(2))

      val c3 = waitForChild.get
      awaitAssert(c3 should !==(c2))
      watch(c3)
      c3 ! PoisonPill
      expectTerminated(c3)
      expectTerminated(supervisor)
    }

    "stop restarting the child after reaching maxNrOfRetries limit (Backoff.onFailure)" in {
      filterException[TestException] {
        val supervisor = create(onFailureOptions(maxNrOfRetries = 2))

        def waitForChild: Option[ActorRef] = {
          eventually(timeout(1.second), interval(50.millis)) {
            supervisor ! BackoffSupervisor.GetCurrentChild
            val c = expectMsgType[BackoffSupervisor.CurrentChild].ref
            c.isDefined shouldBe true
          }

          supervisor ! BackoffSupervisor.GetCurrentChild
          expectMsgType[BackoffSupervisor.CurrentChild].ref
        }

        watch(supervisor)

        supervisor ! BackoffSupervisor.GetRestartCount
        expectMsg(BackoffSupervisor.RestartCount(0))

        supervisor ! BackoffSupervisor.GetCurrentChild
        val c1 = expectMsgType[BackoffSupervisor.CurrentChild].ref.get
        watch(c1)
        c1 ! "boom"
        expectTerminated(c1)

        supervisor ! BackoffSupervisor.GetRestartCount
        expectMsg(BackoffSupervisor.RestartCount(1))

        val c2 = waitForChild.get
        awaitAssert(c2 should !==(c1))
        watch(c2)
        c2 ! "boom"
        expectTerminated(c2)

        supervisor ! BackoffSupervisor.GetRestartCount
        expectMsg(BackoffSupervisor.RestartCount(2))

        val c3 = waitForChild.get
        awaitAssert(c3 should !==(c2))
        watch(c3)
        c3 ! "boom"
        withClue("Expected child and supervisor to terminate") {
          Set(expectMsgType[Terminated].actor, expectMsgType[Terminated].actor) shouldEqual Set(c3, supervisor)
        }

      }
    }

    "stop restarting the child if final stop message received (Backoff.onStop)" in {
      val stopMessage = "stop"
      val supervisor: ActorRef = create(onStopOptions(maxNrOfRetries = 100).withFinalStopMessage(_ == stopMessage))
      supervisor ! BackoffSupervisor.GetCurrentChild
      val c1 = expectMsgType[BackoffSupervisor.CurrentChild].ref.get
      watch(c1)
      watch(supervisor)

      supervisor ! stopMessage
      expectMsg("stop")
      c1 ! PoisonPill
      expectTerminated(c1)
      expectTerminated(supervisor)
    }

    "supervisor must not stop when final stop message has not been received" in {
      val stopMessage = "stop"
      val supervisorWatcher = TestProbe()
      val supervisor: ActorRef = create(onStopOptions(maxNrOfRetries = 100).withFinalStopMessage(_ == stopMessage))
      supervisor ! BackoffSupervisor.GetCurrentChild
      val c1 = expectMsgType[BackoffSupervisor.CurrentChild].ref.get
      watch(c1)
      watch(supervisor)
      supervisorWatcher.watch(supervisor)

      c1 ! PoisonPill
      expectTerminated(c1)
      supervisor ! "ping"
      supervisorWatcher.expectNoMessage(20.millis) // supervisor must not terminate

      supervisor ! stopMessage
      expectTerminated(supervisor)
    }
  }
}
