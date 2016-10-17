/**
 * Copyright (C) 2015-2016 Lightbend Inc. <http://www.lightbend.com>
 */

package akka.pattern

import java.util.concurrent.{ TimeUnit, CountDownLatch }

import akka.pattern.TestActor.NormalException
import akka.testkit.{ ImplicitSender, AkkaSpec, TestProbe, filterException }
import scala.concurrent.duration._
import akka.actor._
import scala.language.postfixOps

object TestActor {

  class TestException(msg: String) extends Exception(msg)

  class StoppingException extends TestException("stopping exception")

  class NormalException extends TestException("normal exception")

  def props(probe: ActorRef): Props = Props(new TestActor(probe))
}

class TestActor(probe: ActorRef) extends Actor {

  probe ! "STARTED"

  def receive = {
    case "DIE"                      ⇒ context.stop(self)
    case "THROW"                    ⇒ throw new TestActor.NormalException
    case "THROW_STOPPING_EXCEPTION" ⇒ throw new TestActor.StoppingException
    case ("TO_PARENT", msg)         ⇒ context.parent ! msg
    case other                      ⇒ probe ! other
  }
}

object TestParentActor {
  def props(probe: ActorRef, supervisorProps: Props): Props =
    Props(new TestParentActor(probe, supervisorProps))
}

class TestParentActor(probe: ActorRef, supervisorProps: Props) extends Actor {
  val supervisor = context.actorOf(supervisorProps)

  def receive = {
    case other ⇒ probe.forward(other)
  }
}

class BackoffOnRestartSupervisorSpec extends AkkaSpec with ImplicitSender {

  def supervisorProps(probeRef: ActorRef) = {
    val options = Backoff.onFailure(TestActor.props(probeRef), "someChildName", 200 millis, 10 seconds, 0.0)
      .withSupervisorStrategy(OneForOneStrategy(maxNrOfRetries = 4, withinTimeRange = 30 seconds) {
        case _: TestActor.StoppingException ⇒ SupervisorStrategy.Stop
      })
    BackoffSupervisor.props(options)
  }

  trait Setup {
    val probe = TestProbe()
    val supervisor = system.actorOf(supervisorProps(probe.ref))
    probe.expectMsg("STARTED")
  }

  trait Setup2 {
    val probe = TestProbe()
    val parent = system.actorOf(TestParentActor.props(probe.ref, supervisorProps(probe.ref)))
    probe.expectMsg("STARTED")
    val child = probe.lastSender
  }

  "BackoffOnRestartSupervisor" must {
    "terminate when child terminates" in new Setup {
      filterException[TestActor.TestException] {
        probe.watch(supervisor)
        supervisor ! "DIE"
        probe.expectTerminated(supervisor)
      }
    }

    "restart the child with an exponential back off" in new Setup {
      filterException[TestActor.TestException] {
        // Exponential back off restart test
        probe.within(1.4 seconds, 2 seconds) {
          supervisor ! "THROW"
          // numRestart = 0 ~ 200 millis
          probe.expectMsg(300 millis, "STARTED")

          supervisor ! "THROW"
          // numRestart = 1 ~ 400 millis
          probe.expectMsg(500 millis, "STARTED")

          supervisor ! "THROW"
          // numRestart = 2 ~ 800 millis
          probe.expectMsg(900 millis, "STARTED")
        }

        // Verify that we only have one child at this point by selecting all the children
        // under the supervisor and broadcasting to them.
        // If there exists more than one child, we will get more than one reply.
        val supervisorChildSelection = system.actorSelection(supervisor.path / "*")
        supervisorChildSelection.tell("testmsg", probe.ref)
        probe.expectMsg("testmsg")
        probe.expectNoMsg
      }
    }

    "stop on exceptions as dictated by the supervisor strategy" in new Setup {
      filterException[TestActor.TestException] {
        probe.watch(supervisor)
        // This should cause the supervisor to stop the child actor and then
        // subsequently stop itself.
        supervisor ! "THROW_STOPPING_EXCEPTION"
        probe.expectTerminated(supervisor)
      }
    }

    "forward messages from the child to the parent of the supervisor" in new Setup2 {
      child ! (("TO_PARENT", "TEST_MESSAGE"))
      probe.expectMsg("TEST_MESSAGE")
    }

    class SlowlyFailingActor(latch: CountDownLatch) extends Actor {
      def receive = {
        case "THROW" ⇒
          sender ! "THROWN"
          throw new NormalException
        case "PING" ⇒
          sender ! "PONG"
      }

      override def postStop(): Unit = {
        latch.await(3, TimeUnit.SECONDS)
      }
    }

    "accept commands while child is terminating" in {
      val postStopLatch = new CountDownLatch(1)
      val options = Backoff.onFailure(Props(new SlowlyFailingActor(postStopLatch)), "someChildName", 1 nanos, 1 nanos, 0.0)
        .withSupervisorStrategy(OneForOneStrategy(loggingEnabled = false) {
          case _: TestActor.StoppingException ⇒ SupervisorStrategy.Stop
        })
      val supervisor = system.actorOf(BackoffSupervisor.props(options))

      supervisor ! BackoffSupervisor.GetCurrentChild
      // new instance
      val Some(child) = expectMsgType[BackoffSupervisor.CurrentChild].ref

      child ! "PING"
      expectMsg("PONG")

      supervisor ! "THROW"
      expectMsg("THROWN")

      child ! "PING"
      expectNoMsg(100.millis) // Child is in limbo due to latch in postStop. There is no Terminated message yet

      supervisor ! BackoffSupervisor.GetCurrentChild
      expectMsgType[BackoffSupervisor.CurrentChild].ref should ===(Some(child))

      supervisor ! BackoffSupervisor.GetRestartCount
      expectMsg(BackoffSupervisor.RestartCount(0))

      postStopLatch.countDown()

      // New child is ready
      awaitAssert {
        supervisor ! BackoffSupervisor.GetCurrentChild
        // new instance
        expectMsgType[BackoffSupervisor.CurrentChild].ref.get should !==(child)
      }

    }

    "respect maxNrOfRetries property of OneForOneStrategy" in new Setup {
      filterException[TestActor.TestException] {
        probe.watch(supervisor)
        // Have the child throw an exception 5 times, which is more than the max restarts allowed.
        for (i ← 1 to 5) {
          supervisor ! "THROW"
          if (i < 5) {
            // Since we should've died on this throw, don't expect to be started.
            // We're not testing timing, so set a reasonably high timeout.
            probe.expectMsg(4 seconds, "STARTED")
          }
        }
        // Supervisor should've terminated.
        probe.expectTerminated(supervisor)
      }
    }

    "respect withinTimeRange property of OneForOneStrategy" in {
      val probe = TestProbe()
      // withinTimeRange indicates the time range in which maxNrOfRetries will cause the child to
      // stop. IE: If we restart more than maxNrOfRetries in a time range longer than withinTimeRange
      // that is acceptable.
      val options = Backoff.onFailure(TestActor.props(probe.ref), "someChildName", 300 millis, 10 seconds, 0.0)
        .withSupervisorStrategy(OneForOneStrategy(withinTimeRange = 1 seconds, maxNrOfRetries = 3) {
          case _: TestActor.StoppingException ⇒ SupervisorStrategy.Stop
        })
      val supervisor = system.actorOf(BackoffSupervisor.props(options))
      probe.expectMsg("STARTED")
      filterException[TestActor.TestException] {
        probe.watch(supervisor)
        // Throw three times rapidly
        for (i ← 1 to 3) {
          supervisor ! "THROW"
          probe.expectMsg("STARTED")
        }
        // Now wait the length of our window, and throw again. We should still restart.
        Thread.sleep(1000)
        supervisor ! "THROW"
        probe.expectMsg("STARTED")
        // Now we'll issue three more requests, and should be terminated.
        supervisor ! "THROW"
        probe.expectMsg("STARTED")
        supervisor ! "THROW"
        probe.expectMsg("STARTED")
        supervisor ! "THROW"
        probe.expectTerminated(supervisor)
      }
    }
  }
}
