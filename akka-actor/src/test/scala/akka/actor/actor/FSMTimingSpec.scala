package akka.actor

import akka.util.TestKit
import akka.util.duration._

import org.scalatest.Spec
import org.scalatest.matchers.ShouldMatchers
import org.scalatest.junit.JUnitRunner
import org.junit.runner.RunWith

@RunWith(classOf[JUnitRunner])
class FSMTimingSpec
        extends Spec
        with ShouldMatchers
        with TestKit {

    import FSMTimingSpec._
    import FSM._

    val fsm = Actor.actorOf(new StateMachine(testActor)).start
    fsm ! SubscribeTransitionCallBack(testActor)
    expectMsg(50 millis, Initial)

    ignoreMsg {
        case Transition(Initial, _) => true
    }

    describe("A Finite State Machine") {

        it("should receive StateTimeout") {
            within (50 millis, 150 millis) {
                fsm ! TestStateTimeout
                expectMsg(Transition(TestStateTimeout, Initial))
                expectNoMsg
            }
        }

        it("should receive single-shot timer") {
            within (50 millis, 150 millis) {
                fsm ! TestSingleTimer
                expectMsg(Tick)
                expectMsg(Transition(TestSingleTimer, Initial))
                expectNoMsg
            }
        }

        it("should receive and cancel a repeated timer") {
            fsm ! TestRepeatedTimer
            val seq = receiveWhile(550 millis) {
                case Tick => Tick
            }
            seq should have length (5)
            within(250 millis) {
                fsm ! Cancel
                expectMsg(Transition(TestRepeatedTimer, Initial))
                expectNoMsg
            }
        }

        it("should notify unhandled messages") {
            fsm ! Cancel
            expectMsg(Unhandled(Cancel))
        }

    }

}

object FSMTimingSpec {

    trait State
    case object Initial extends State
    case object TestStateTimeout extends State
    case object TestSingleTimer extends State
    case object TestRepeatedTimer extends State

    case object Tick
    case object Cancel

    case class Unhandled(msg : AnyRef)

    class StateMachine(tester : ActorRef) extends Actor with FSM[State, Unit] {
        import FSM._

        startWith(Initial, ())
        when(Initial) {
            case Ev(TestStateTimeout) => goto(TestStateTimeout)
            case Ev(TestSingleTimer) =>
                setTimer("tester", Tick, 100 millis, false)
                goto(TestSingleTimer)
            case Ev(TestRepeatedTimer) =>
                setTimer("tester", Tick, 100 millis, true)
                goto(TestRepeatedTimer)
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

        whenUnhandled {
            case Ev(msg : AnyRef) =>
                tester ! Unhandled(msg)
                stay
        }
    }

}

// vim: set ts=4 sw=4 et:
