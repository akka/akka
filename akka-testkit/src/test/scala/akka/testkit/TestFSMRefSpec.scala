/**
 * Copyright (C) 2009-2011 Scalable Solutions AB <http://scalablesolutions.se>
 */

package akka.testkit

import org.scalatest.matchers.MustMatchers
import org.scalatest.{ BeforeAndAfterEach, WordSpec }
import akka.actor._
import akka.util.duration._

class TestFSMRefSpec extends WordSpec with MustMatchers with TestKit {

  import FSM._

  "A TestFSMRef" must {

    "allow access to state data" in {
      val fsm = TestFSMRef(new Actor with FSM[Int, String] {
        startWith(1, "")
        when(1) {
          case Ev("go")         ⇒ goto(2) using "go"
          case Ev(StateTimeout) ⇒ goto(2) using "timeout"
        }
        when(2) {
          case Ev("back") ⇒ goto(1) using "back"
        }
      }).start()
      fsm.stateName must be(1)
      fsm.stateData must be("")
      fsm ! "go"
      fsm.stateName must be(2)
      fsm.stateData must be("go")
      fsm.setState(stateName = 1)
      fsm.stateName must be(1)
      fsm.stateData must be("go")
      fsm.setState(stateData = "buh")
      fsm.stateName must be(1)
      fsm.stateData must be("buh")
      fsm.setState(timeout = 100 millis)
      within(80 millis, 500 millis) {
        awaitCond(fsm.stateName == 2 && fsm.stateData == "timeout")
      }
    }

    "allow access to timers" in {
      val fsm = TestFSMRef(new Actor with FSM[Int, Null] {
        startWith(1, null)
        when(1) {
          case x ⇒ stay
        }
      })
      fsm.timerActive_?("test") must be(false)
      fsm.setTimer("test", 12, 10 millis, true)
      fsm.timerActive_?("test") must be(true)
      fsm.cancelTimer("test")
      fsm.timerActive_?("test") must be(false)
    }

  }

}
