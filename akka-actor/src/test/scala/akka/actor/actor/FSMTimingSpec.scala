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

    val fsm = Actor.actorOf[StateMachine].start
    fsm ! SubscribeTransitionCallBack(testActor)
    expectMsg(Initial)

    ignoreMsg {
        case Transition(Initial, _) => true
    }

    describe("A Finite State Machine") {

        it("should receive StateTimeout") {
            within (50 millis, 150 millis) {
                fsm ! TestStateTimeout
                expectMsg(Transition(TestStateTimeout, Initial))
            }
        }

    }

}

object FSMTimingSpec {

    trait State
    case object Initial extends State
    case object TestStateTimeout extends State

    class StateMachine extends Actor with FSM[State, Unit] {
        import FSM._

        startWith(Initial, ())
        when(Initial) {
            case Event(TestStateTimeout, _) => goto(TestStateTimeout)
        }
        when(TestStateTimeout, stateTimeout = 100 millis) {
            case Event(StateTimeout, _) => goto(Initial)
        }
    }

}

// vim: set ts=4 sw=4 et:
