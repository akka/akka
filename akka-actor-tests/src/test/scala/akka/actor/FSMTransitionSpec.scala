/**
 * Copyright (C) 2009-2012 Typesafe Inc. <http://www.typesafe.com>
 */
package akka.actor

import akka.testkit._
import akka.util.duration._

import FSM._

object FSMTransitionSpec {

  class Supervisor extends Actor {
    def receive = { case _ ⇒ }
  }

  class MyFSM(target: ActorRef) extends Actor with FSM[Int, Unit] {
    startWith(0, Unit)
    when(0) {
      case Ev("tick") ⇒ goto(1)
    }
    when(1) {
      case Ev("tick") ⇒ goto(0)
    }
    whenUnhandled {
      case Ev("reply") ⇒ stay replying "reply"
    }
    initialize
    override def preRestart(reason: Throwable, msg: Option[Any]) { target ! "restarted" }
  }

  class OtherFSM(target: ActorRef) extends Actor with FSM[Int, Int] {
    startWith(0, 0)
    when(0) {
      case Ev("tick") ⇒ goto(1) using (1)
    }
    when(1) {
      case Ev(_) ⇒ stay
    }
    onTransition {
      case 0 -> 1 ⇒ target ! ((stateData, nextStateData))
    }
  }

  class Forwarder(target: ActorRef) extends Actor {
    def receive = { case x ⇒ target ! x }
  }

}

@org.junit.runner.RunWith(classOf[org.scalatest.junit.JUnitRunner])
class FSMTransitionSpec extends AkkaSpec with ImplicitSender {

  import FSMTransitionSpec._

  "A FSM transition notifier" must {

    "notify listeners" in {
      val fsm = system.actorOf(Props(new MyFSM(testActor)))
      within(1 second) {
        fsm ! SubscribeTransitionCallBack(testActor)
        expectMsg(CurrentState(fsm, 0))
        fsm ! "tick"
        expectMsg(Transition(fsm, 0, 1))
        fsm ! "tick"
        expectMsg(Transition(fsm, 1, 0))
      }
    }

    "not fail when listener goes away" in {
      val forward = system.actorOf(Props(new Forwarder(testActor)))
      val fsm = system.actorOf(Props(new MyFSM(testActor)))
      val sup = system.actorOf(Props(new Actor {
        context.watch(fsm)
        override val supervisorStrategy = OneForOneStrategy(List(classOf[Throwable]), None, None)
        def receive = { case _ ⇒ }
      }))

      within(300 millis) {
        fsm ! SubscribeTransitionCallBack(forward)
        expectMsg(CurrentState(fsm, 0))
        system.stop(forward)
        fsm ! "tick"
        expectNoMsg
      }
    }
  }

  "A FSM" must {

    "make previous and next state data available in onTransition" in {
      val fsm = system.actorOf(Props(new OtherFSM(testActor)))
      within(300 millis) {
        fsm ! "tick"
        expectMsg((0, 1))
      }
    }

  }

}
