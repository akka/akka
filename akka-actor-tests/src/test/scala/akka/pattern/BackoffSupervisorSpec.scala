/**
 * Copyright (C) 2015 Typesafe Inc. <http://www.typesafe.com>
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
}

class BackoffSupervisorSpec extends AkkaSpec with ImplicitSender {
  import BackoffSupervisorSpec._

  "BackoffSupervisor" must {
    "start child again when it stops" in {
      val supervisor = system.actorOf(
        BackoffSupervisor.props(Child.props(testActor), "c1", 100.millis, 3.seconds, 0.2))
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
      val supervisor = system.actorOf(
        BackoffSupervisor.props(Child.props(testActor), "c2", 100.millis, 3.seconds, 0.2))
      supervisor ! "hello"
      expectMsg("hello")
    }

    "support custom supervision decider" in {
      val supervisor = system.actorOf(
        BackoffSupervisor.propsWithSupervisorStrategy(Child.props(testActor), "c1", 100.millis, 3.seconds, 0.2,
          OneForOneStrategy() {
            case _: TestException ⇒ SupervisorStrategy.Stop
          }))
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
  }
}
