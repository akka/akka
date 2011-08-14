/**
 * Copyright (C) 2009-2011 Scalable Solutions AB <http://scalablesolutions.se>
 */

package akka.actor

import org.scalatest.WordSpec
import org.scalatest.matchers.MustMatchers

import akka.testkit.TestKit
import akka.util.Duration
import akka.util.duration._

class FSMTimingSpec extends WordSpec with MustMatchers with TestKit {
  import FSMTimingSpec._
  import FSM._

  val fsm = Actor.actorOf(new StateMachine(testActor)).start()
  fsm ! SubscribeTransitionCallBack(testActor)
  expectMsg(200 millis, CurrentState(fsm, Initial))

  ignoreMsg {
    case Transition(_, Initial, _) ⇒ true
  }

  "A Finite State Machine" must {

    "receive StateTimeout" in {
      within(50 millis, 150 millis) {
        fsm ! TestStateTimeout
        expectMsg(Transition(fsm, TestStateTimeout, Initial))
        expectNoMsg
      }
    }

    "allow StateTimeout override" in {
      within(500 millis) {
        fsm ! TestStateTimeoutOverride
        expectNoMsg
      }
      within(50 millis) {
        fsm ! Cancel
        expectMsg(Cancel)
        expectMsg(Transition(fsm, TestStateTimeout, Initial))
      }
    }

    "receive single-shot timer" in {
      within(50 millis, 150 millis) {
        fsm ! TestSingleTimer
        expectMsg(Tick)
        expectMsg(Transition(fsm, TestSingleTimer, Initial))
        expectNoMsg
      }
    }

    "correctly cancel a named timer" in {
      fsm ! TestCancelTimer
      within(100 millis, 200 millis) {
        fsm ! Tick
        expectMsg(Tick)
        expectMsg(Tock)
      }
      fsm ! Cancel
      expectMsg(Transition(fsm, TestCancelTimer, Initial))
    }

    "not get confused between named and state timers" in {
      fsm ! TestCancelStateTimerInNamedTimerMessage
      fsm ! Tick
      expectMsg(100 millis, Tick)
      Thread.sleep(200)
      fsm.dispatcher resume fsm
      expectMsg(100 millis, Transition(fsm, TestCancelStateTimerInNamedTimerMessage, TestCancelStateTimerInNamedTimerMessage2))
      fsm ! Cancel
      within(100 millis) {
        expectMsg(Cancel)
        expectMsg(Transition(fsm, TestCancelStateTimerInNamedTimerMessage2, Initial))
      }
    }

    "receive and cancel a repeated timer" in {
      fsm ! TestRepeatedTimer
      val seq = receiveWhile(600 millis) {
        case Tick ⇒ Tick
      }
      seq must have length (5)
      within(250 millis) {
        expectMsg(Transition(fsm, TestRepeatedTimer, Initial))
        expectNoMsg
      }
    }

    "notify unhandled messages" in {
      fsm ! TestUnhandled
      within(200 millis) {
        fsm ! Tick
        expectNoMsg
      }
      within(200 millis) {
        fsm ! SetHandler
        fsm ! Tick
        expectMsg(Unhandled(Tick))
        expectNoMsg
      }
      within(200 millis) {
        fsm ! Unhandled("test")
        expectNoMsg
      }
      within(200 millis) {
        fsm ! Cancel
        expectMsg(Transition(fsm, TestUnhandled, Initial))
      }
    }

  }

}

object FSMTimingSpec {

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
        setTimer("tester", Tick, 100 millis, false)
        goto(TestSingleTimer)
      case Ev(TestRepeatedTimer) ⇒
        setTimer("tester", Tick, 100 millis, true)
        goto(TestRepeatedTimer) using 4
      case Ev(TestStateTimeoutOverride) ⇒
        goto(TestStateTimeout) forMax (Duration.Inf)
      case Ev(x: FSMTimingSpec.State) ⇒ goto(x)
    }
    when(TestStateTimeout, stateTimeout = 100 millis) {
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
        Thread.sleep(10);
        cancelTimer("hallo")
        setTimer("hallo", Tock, 100 millis, false)
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
      // FSM is suspended after processing this message and resumed 200ms later
      case Ev(Tick) ⇒
        self.dispatcher suspend self
        setTimer("named", Tock, 10 millis, false)
        stay forMax (100 millis) replying Tick
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

