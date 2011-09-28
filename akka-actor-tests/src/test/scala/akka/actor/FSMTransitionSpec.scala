/**
 * Copyright (C) 2009-2011 Typesafe Inc. <http://www.typesafe.com>
 */
package akka.actor

import org.scalatest.WordSpec
import org.scalatest.matchers.MustMatchers

import akka.testkit._
import akka.testkit._
import akka.util.duration._
import akka.config.Supervision._
import akka.event.EventHandler

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

class FSMTransitionSpec extends WordSpec with MustMatchers with TestKit {

  import FSMTransitionSpec._

  "A FSM transition notifier" must {

    "notify listeners" in {
      val fsm = Actor.actorOf(new MyFSM(testActor))
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
      val forward = Actor.actorOf(new Forwarder(testActor))
      val fsm = Actor.actorOf(new MyFSM(testActor))
      val sup = Actor.actorOf(Props[Supervisor].withFaultHandler(OneForOneStrategy(List(classOf[Throwable]), None, None)))
      sup link fsm
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
