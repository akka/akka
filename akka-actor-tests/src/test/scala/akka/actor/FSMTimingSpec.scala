/**
 * Copyright (C) 2009-2011 Typesafe Inc. <http://www.typesafe.com>
 */

package akka.actor

import akka.testkit._
import akka.util.Duration
import akka.util.duration._
import akka.event.Logging

@org.junit.runner.RunWith(classOf[org.scalatest.junit.JUnitRunner])
class FSMTimingSpec extends AkkaSpec with ImplicitSender {
  import FSMTimingSpec._
  import FSM._

  val fsm = actorOf(new StateMachine(testActor))
  fsm ! SubscribeTransitionCallBack(testActor)
  expectMsg(1 second, CurrentState(fsm, Initial))

  ignoreMsg {
    case Transition(_, Initial, _) ⇒ true
  }

  "A Finite State Machine" must {

    "receive StateTimeout" in {
      within(1 second) {
        within(500 millis, 1 second) {
          fsm ! TestStateTimeout
          expectMsg(Transition(fsm, TestStateTimeout, Initial))
        }
        expectNoMsg
      }
    }

    "cancel a StateTimeout" in {
      within(1 second) {
        fsm ! TestStateTimeout
        fsm ! Cancel
        expectMsg(Cancel)
        expectMsg(Transition(fsm, TestStateTimeout, Initial))
        expectNoMsg
      }
    }

    "allow StateTimeout override" in {
      within(500 millis) {
        fsm ! TestStateTimeoutOverride
        expectNoMsg
      }
      within(500 millis) {
        fsm ! Cancel
        expectMsg(Cancel)
        expectMsg(Transition(fsm, TestStateTimeout, Initial))
      }
    }

    "receive single-shot timer" in {
      within(1.5 seconds) {
        within(500 millis, 1 second) {
          fsm ! TestSingleTimer
          expectMsg(Tick)
          expectMsg(Transition(fsm, TestSingleTimer, Initial))
        }
        expectNoMsg
      }
    }

    "correctly cancel a named timer" in {
      fsm ! TestCancelTimer
      within(500 millis) {
        fsm ! Tick
        expectMsg(Tick)
      }
      within(300 millis, 1 second) {
        expectMsg(Tock)
      }
      fsm ! Cancel
      expectMsg(1 second, Transition(fsm, TestCancelTimer, Initial))
    }

    "not get confused between named and state timers" in {
      fsm ! TestCancelStateTimerInNamedTimerMessage
      fsm ! Tick
      expectMsg(500 millis, Tick)
      Thread.sleep(200) // this is ugly: need to wait for StateTimeout to be queued
      resume(fsm)
      expectMsg(500 millis, Transition(fsm, TestCancelStateTimerInNamedTimerMessage, TestCancelStateTimerInNamedTimerMessage2))
      fsm ! Cancel
      within(500 millis) {
        expectMsg(Cancel) // if this is not received, that means StateTimeout was not properly discarded
        expectMsg(Transition(fsm, TestCancelStateTimerInNamedTimerMessage2, Initial))
      }
    }

    "receive and cancel a repeated timer" in {
      fsm ! TestRepeatedTimer
      val seq = receiveWhile(1 second) {
        case Tick ⇒ Tick
      }
      seq must have length 5
      within(500 millis) {
        expectMsg(Transition(fsm, TestRepeatedTimer, Initial))
      }
    }

    "notify unhandled messages" in {
      filterEvents(EventFilter.custom {
        case Logging.Warning(`fsm`, "unhandled event Tick in state TestUnhandled") ⇒ true
        case Logging.Warning(`fsm`, "unhandled event Unhandled(test) in state TestUnhandled") ⇒ true
        case _ ⇒ false
      }) {
        fsm ! TestUnhandled
        within(1 second) {
          fsm ! Tick
          fsm ! SetHandler
          fsm ! Tick
          expectMsg(Unhandled(Tick))
          fsm ! Unhandled("test")
          fsm ! Cancel
          expectMsg(Transition(fsm, TestUnhandled, Initial))
        }
      }
    }

  }

}

object FSMTimingSpec {

  def suspend(actorRef: ActorRef): Unit = actorRef match {
    case l: LocalActorRef ⇒ l.suspend()
    case _                ⇒
  }

  def resume(actorRef: ActorRef): Unit = actorRef match {
    case l: LocalActorRef ⇒ l.resume()
    case _                ⇒
  }

  trait State
  case object Initial extends State
  case object TestStateTimeout extends State
  case object TestStateTimeoutOverride extends State
  case object TestSingleTimer extends State
  case object TestRepeatedTimer extends State
  case object TestUnhandled extends State
  case object TestCancelTimer extends State
  case object TestCancelStateTimerInNamedTimerMessage extends State
  case object TestCancelStateTimerInNamedTimerMessage2 extends State

  case object Tick
  case object Tock
  case object Cancel
  case object SetHandler

  case class Unhandled(msg: AnyRef)

  class StateMachine(tester: ActorRef) extends Actor with FSM[State, Int] {
    import FSM._

    startWith(Initial, 0)
    when(Initial) {
      case Ev(TestSingleTimer) ⇒
        setTimer("tester", Tick, 500 millis, false)
        goto(TestSingleTimer)
      case Ev(TestRepeatedTimer) ⇒
        setTimer("tester", Tick, 100 millis, true)
        goto(TestRepeatedTimer) using 4
      case Ev(TestStateTimeoutOverride) ⇒
        goto(TestStateTimeout) forMax (Duration.Inf)
      case Ev(x: FSMTimingSpec.State) ⇒ goto(x)
    }
    when(TestStateTimeout, stateTimeout = 500 millis) {
      case Ev(StateTimeout) ⇒ goto(Initial)
      case Ev(Cancel)       ⇒ goto(Initial) replying (Cancel)
    }
    when(TestSingleTimer) {
      case Ev(Tick) ⇒
        tester ! Tick
        goto(Initial)
    }
    when(TestCancelTimer) {
      case Ev(Tick) ⇒
        tester ! Tick
        setTimer("hallo", Tock, 1 milli, false)
        TestKit.awaitCond(context.hasMessages, 1 second)
        cancelTimer("hallo")
        setTimer("hallo", Tock, 500 millis, false)
        stay
      case Ev(Tock) ⇒
        tester ! Tock
        stay
      case Ev(Cancel) ⇒
        cancelTimer("hallo")
        goto(Initial)
    }
    when(TestRepeatedTimer) {
      case Event(Tick, remaining) ⇒
        tester ! Tick
        if (remaining == 0) {
          cancelTimer("tester")
          goto(Initial)
        } else {
          stay using (remaining - 1)
        }
    }
    when(TestCancelStateTimerInNamedTimerMessage) {
      // FSM is suspended after processing this message and resumed 500ms later
      case Ev(Tick) ⇒
        suspend(self)
        setTimer("named", Tock, 1 millis, false)
        TestKit.awaitCond(context.hasMessages, 1 second)
        stay forMax (1 millis) replying Tick
      case Ev(Tock) ⇒
        goto(TestCancelStateTimerInNamedTimerMessage2)
    }
    when(TestCancelStateTimerInNamedTimerMessage2) {
      case Ev(StateTimeout) ⇒
        goto(Initial)
      case Ev(Cancel) ⇒
        goto(Initial) replying Cancel
    }
    when(TestUnhandled) {
      case Ev(SetHandler) ⇒
        whenUnhandled {
          case Ev(Tick) ⇒
            tester ! Unhandled(Tick)
            stay
        }
        stay
      case Ev(Cancel) ⇒
        whenUnhandled(NullFunction)
        goto(Initial)
    }
  }

}

