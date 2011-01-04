package akka.actor

import akka.util.TestKit
import akka.util.duration._

import org.scalatest.WordSpec
import org.scalatest.matchers.MustMatchers

class FSMTimingSpec
    extends WordSpec
    with MustMatchers
    with TestKit {

  import FSMTimingSpec._
  import FSM._

  val fsm = Actor.actorOf(new StateMachine(testActor)).start
  fsm ! SubscribeTransitionCallBack(testActor)
  expectMsg(100 millis, CurrentState(fsm, Initial))

  ignoreMsg {
      case Transition(_, Initial, _) => true
  }

  "A Finite State Machine" must {

    "receive StateTimeout" in {
      within (50 millis, 150 millis) {
        fsm ! TestStateTimeout
        expectMsg(Transition(fsm, TestStateTimeout, Initial))
        expectNoMsg
      }
    }

    "receive single-shot timer" in {
      within (50 millis, 150 millis) {
        fsm ! TestSingleTimer
        expectMsg(Tick)
        expectMsg(Transition(fsm, TestSingleTimer, Initial))
        expectNoMsg
      }
    }

    "receive and cancel a repeated timer" in {
      fsm ! TestRepeatedTimer
      val seq = receiveWhile(550 millis) {
        case Tick => Tick
      }
      seq must have length (5)
      within(250 millis) {
        fsm ! Cancel
        expectMsg(Transition(fsm, TestRepeatedTimer, Initial))
        expectNoMsg
      }
    }

    "notify unhandled messages" in {
      fsm ! TestUnhandled
      within(100 millis) {
        fsm ! Tick
        expectNoMsg
      }
      within(100 millis) {
        fsm ! SetHandler
        fsm ! Tick
        expectMsg(Unhandled(Tick))
        expectNoMsg
      }
      within(100 millis) {
        fsm ! Unhandled("test")
        expectNoMsg
      }
      within(100 millis) {
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
  case object TestSingleTimer extends State
  case object TestRepeatedTimer extends State
  case object TestUnhandled extends State

  case object Tick
  case object Cancel
  case object SetHandler

  case class Unhandled(msg : AnyRef)

  class StateMachine(tester : ActorRef) extends Actor with FSM[State, Unit] {
    import FSM._

    startWith(Initial, ())
    when(Initial) {
      case Ev(TestSingleTimer) =>
        setTimer("tester", Tick, 100 millis, false)
        goto(TestSingleTimer)
      case Ev(TestRepeatedTimer) =>
        setTimer("tester", Tick, 100 millis, true)
        goto(TestRepeatedTimer)
      case Ev(x : FSMTimingSpec.State) => goto(x)
    }
    when(TestStateTimeout, stateTimeout = 100 millis) {
      case Ev(StateTimeout) => goto(Initial)
    }
    when(TestSingleTimer) {
      case Ev(Tick) =>
        tester ! Tick
        goto(Initial)
    }
    when(TestRepeatedTimer) {
      case Ev(Tick) =>
        tester ! Tick
        stay
      case Ev(Cancel) =>
        cancelTimer("tester")
        goto(Initial)
    }
    when(TestUnhandled) {
      case Ev(SetHandler) =>
        whenUnhandled {
          case Ev(Tick) =>
            tester ! Unhandled(Tick)
            stay
        }
        stay
      case Ev(Cancel) =>
        goto(Initial)
    }
  }

}

// vim: set ts=2 sw=2 et:
