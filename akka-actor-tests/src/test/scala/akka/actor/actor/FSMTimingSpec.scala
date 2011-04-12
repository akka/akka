package akka.actor

import org.scalatest.WordSpec
import org.scalatest.matchers.MustMatchers

import akka.testkit.TestKit
import akka.util.duration._


class FSMTimingSpec extends WordSpec with MustMatchers with TestKit {
  import FSMTimingSpec._
  import FSM._

  val fsm = Actor.actorOf(new StateMachine(testActor)).start()
  fsm ! SubscribeTransitionCallBack(testActor)
  expectMsg(200 millis, CurrentState(fsm, Initial))

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
      val seq = receiveWhile(600 millis) {
        case Tick => Tick
      }
      seq must have length (5)
      within(250 millis) {
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

  class StateMachine(tester : ActorRef) extends Actor with FSM[State, Int] {
    import FSM._

    startWith(Initial, 0)
    when(Initial) {
      case Ev(TestSingleTimer) =>
        setTimer("tester", Tick, 100 millis, false)
        goto(TestSingleTimer)
      case Ev(TestRepeatedTimer) =>
        setTimer("tester", Tick, 100 millis, true)
        goto(TestRepeatedTimer) using 4
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
      case Event(Tick, remaining) =>
        tester ! Tick
        if (remaining == 0) {
          cancelTimer("tester")
          goto(Initial)
        } else {
          stay using (remaining - 1)
        }
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

