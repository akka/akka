/**
 * Copyright (C) 2009-2011 Typesafe Inc. <http://www.typesafe.com>
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

  class Forwarder(target: ActorRef) extends Actor {
    def receive = { case x ⇒ target ! x }
  }

}

@org.junit.runner.RunWith(classOf[org.scalatest.junit.JUnitRunner])
class FSMTransitionSpec extends AkkaSpec with ImplicitSender {

  import FSMTransitionSpec._

  "A FSM transition notifier" must {

    "notify listeners" in {
      val fsm = actorOf(new MyFSM(testActor))
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
      val forward = actorOf(new Forwarder(testActor))
      val fsm = actorOf(new MyFSM(testActor))
      val sup = actorOf(Props(new Actor {
        context.watch(fsm)
        def receive = { case _ ⇒ }
      }).withFaultHandler(OneForOneStrategy(List(classOf[Throwable]), None, None)))

      within(300 millis) {
        fsm ! SubscribeTransitionCallBack(forward)
        expectMsg(CurrentState(fsm, 0))
        forward.stop()
        fsm ! "tick"
        expectNoMsg
      }
    }
  }

}
