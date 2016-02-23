/**
 * Copyright (C) 2015-2016 Lightbend Inc. <http://www.lightbend.com>
 */

package akka.pattern

import scala.concurrent.duration._
import akka.actor._
import akka.testkit._
import scala.util.control.NoStackTrace
import akka.actor.SupervisorStrategy

object BackoffSupervisorSpec {

  class TestException extends RuntimeException with NoStackTrace

  object Child {
    def props(probe: ActorRef): Props =
      Props(new Child(probe))
  }

  class Child(probe: ActorRef) extends Actor {
    def receive = {
      case "boom" ⇒ throw new TestException
      case msg    ⇒ probe ! msg
    }
  }

  object ManualChild {
    def props(probe: ActorRef): Props =
      Props(new ManualChild(probe))
  }

  class ManualChild(probe: ActorRef) extends Actor {
    def receive = {
      case "boom" ⇒ throw new TestException
      case msg ⇒
        probe ! msg
        context.parent ! BackoffSupervisor.Reset
    }
  }
}

class BackoffSupervisorSpec extends AkkaSpec with ImplicitSender {
  import BackoffSupervisorSpec._

  def onStopOptions(props: Props = Child.props(testActor)) = Backoff.onStop(props, "c1", 100.millis, 3.seconds, 0.2)
  def onFailureOptions(props: Props = Child.props(testActor)) = Backoff.onFailure(props, "c1", 100.millis, 3.seconds, 0.2)
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
          case _: TestException ⇒ SupervisorStrategy.Stop
        }
        val restartingStrategy = OneForOneStrategy() {
          case _: TestException ⇒ SupervisorStrategy.Restart
        }

        assertCustomStrategy(
          create(onStopOptions()
            .withSupervisorStrategy(stoppingStrategy)))

        assertCustomStrategy(
          create(onFailureOptions()
            .withSupervisorStrategy(restartingStrategy)))
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
          case _: TestException ⇒ SupervisorStrategy.Stop
        }
        val restartingStrategy = OneForOneStrategy() {
          case _: TestException ⇒ SupervisorStrategy.Restart
        }

        assertManualReset(
          create(onStopOptions(ManualChild.props(testActor))
            .withManualReset
            .withSupervisorStrategy(stoppingStrategy)))

        assertManualReset(
          create(onFailureOptions(ManualChild.props(testActor))
            .withManualReset
            .withSupervisorStrategy(restartingStrategy)))
      }
    }
  }
}
