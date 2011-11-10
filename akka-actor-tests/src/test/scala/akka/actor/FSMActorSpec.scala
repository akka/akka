/**
 * Copyright (C) 2009-2011 Typesafe Inc. <http://www.typesafe.com>
 */

package akka.actor

import org.scalatest.{ BeforeAndAfterAll, BeforeAndAfterEach }

import akka.testkit._
import TestEvent.Mute
import FSM._
import akka.util.Duration
import akka.util.duration._
import akka.event._
import akka.AkkaApplication
import akka.AkkaApplication.defaultConfig
import akka.config.Configuration

object FSMActorSpec {

  class Latches(implicit app: AkkaApplication) {
    val unlockedLatch = TestLatch()
    val lockedLatch = TestLatch()
    val unhandledLatch = TestLatch()
    val terminatedLatch = TestLatch()
    val transitionLatch = TestLatch()
    val initialStateLatch = TestLatch()
    val transitionCallBackLatch = TestLatch()
  }

  sealed trait LockState
  case object Locked extends LockState
  case object Open extends LockState

  class Lock(code: String, timeout: Duration, latches: Latches) extends Actor with FSM[LockState, CodeState] {

    import latches._

    startWith(Locked, CodeState("", code))

    when(Locked) {
      case Event(digit: Char, CodeState(soFar, code)) ⇒ {
        soFar + digit match {
          case incomplete if incomplete.length < code.length ⇒
            stay using CodeState(incomplete, code)
          case codeTry if (codeTry == code) ⇒ {
            doUnlock
            goto(Open) using CodeState("", code) forMax timeout
          }
          case wrong ⇒ {
            stay using CodeState("", code)
          }
        }
      }
      case Event("hello", _) ⇒ stay replying "world"
      case Event("bye", _)   ⇒ stop(Shutdown)
    }

    when(Open) {
      case Event(StateTimeout, _) ⇒ {
        doLock
        goto(Locked)
      }
    }

    whenUnhandled {
      case Ev(msg) ⇒ {
        log.warning("unhandled event " + msg + " in state " + stateName + " with data " + stateData)
        unhandledLatch.open
        stay
      }
    }

    onTransition {
      case Locked -> Open ⇒ transitionLatch.open
    }

    // verify that old-style does still compile
    onTransition(transitionHandler _)

    def transitionHandler(from: LockState, to: LockState) = {
      // dummy
    }

    onTermination {
      case StopEvent(Shutdown, Locked, _) ⇒
        // stop is called from lockstate with shutdown as reason...
        terminatedLatch.open
    }

    // initialize the lock
    initialize

    private def doLock() {
      lockedLatch.open
    }

    private def doUnlock = {
      unlockedLatch.open
    }
  }

  case class CodeState(soFar: String, code: String)
}

@org.junit.runner.RunWith(classOf[org.scalatest.junit.JUnitRunner])
class FSMActorSpec extends AkkaSpec(Configuration("akka.actor.debug.fsm" -> true)) with ImplicitSender {
  import FSMActorSpec._

  "An FSM Actor" must {

    "unlock the lock" in {

      val latches = new Latches
      import latches._

      // lock that locked after being open for 1 sec
      val lock = actorOf(new Lock("33221", 1 second, latches))

      val transitionTester = actorOf(new Actor {
        def receive = {
          case Transition(_, _, _)     ⇒ transitionCallBackLatch.open
          case CurrentState(_, Locked) ⇒ initialStateLatch.open
        }
      })

      lock ! SubscribeTransitionCallBack(transitionTester)
      initialStateLatch.await

      lock ! '3'
      lock ! '3'
      lock ! '2'
      lock ! '2'
      lock ! '1'

      unlockedLatch.await
      transitionLatch.await
      transitionCallBackLatch.await
      lockedLatch.await

      EventFilter.warning(start = "unhandled event", occurrences = 1) intercept {
        lock ! "not_handled"
        unhandledLatch.await
      }

      val answerLatch = TestLatch()
      object Hello
      object Bye
      val tester = actorOf(new Actor {
        protected def receive = {
          case Hello   ⇒ lock ! "hello"
          case "world" ⇒ answerLatch.open
          case Bye     ⇒ lock ! "bye"
        }
      })
      tester ! Hello
      answerLatch.await

      tester ! Bye
      terminatedLatch.await
    }

    "log termination" in {
      val fsm = TestActorRef(new Actor with FSM[Int, Null] {
        startWith(1, null)
        when(1) {
          case Ev("go") ⇒ goto(2)
        }
      })
      filterException[Logging.EventHandlerException] {
        app.mainbus.subscribe(testActor, classOf[Logging.Error])
        fsm ! "go"
        expectMsgPF(1 second, hint = "Next state 2 does not exist") {
          case Logging.Error(_, `fsm`, "Next state 2 does not exist") ⇒ true
        }
        app.mainbus.unsubscribe(testActor)
      }
    }

    "run onTermination upon ActorRef.stop()" in {
      val started = TestLatch(1)
      lazy val fsm = new Actor with FSM[Int, Null] {
        override def preStart = { started.countDown }
        startWith(1, null)
        when(1) { NullFunction }
        onTermination {
          case x ⇒ testActor ! x
        }
      }
      val ref = actorOf(fsm)
      started.await
      ref.stop()
      expectMsg(1 second, fsm.StopEvent(Shutdown, 1, null))
    }

    "log events and transitions if asked to do so" in {
      new TestKit(AkkaApplication("fsm event", AkkaApplication.defaultConfig ++
        Configuration("akka.loglevel" -> "DEBUG",
          "akka.actor.debug.fsm" -> true))) {
        EventFilter.debug() intercept {
          val fsm = TestActorRef(new Actor with LoggingFSM[Int, Null] {
            startWith(1, null)
            when(1) {
              case Ev("go") ⇒
                setTimer("t", Shutdown, 1.5 seconds, false)
                goto(2)
            }
            when(2) {
              case Ev("stop") ⇒
                cancelTimer("t")
                stop
            }
            onTermination {
              case StopEvent(r, _, _) ⇒ testActor ! r
            }
          })
          app.mainbus.subscribe(testActor, classOf[Logging.Debug])
          fsm ! "go"
          expectMsgPF(1 second, hint = "processing Event(go,null)") {
            case Logging.Debug(`fsm`, s: String) if s.startsWith("processing Event(go,null) from Actor[" + app.address + "/sys/testActor") ⇒ true
          }
          expectMsg(1 second, Logging.Debug(fsm, "setting timer 't'/1500 milliseconds: Shutdown"))
          expectMsg(1 second, Logging.Debug(fsm, "transition 1 -> 2"))
          fsm ! "stop"
          expectMsgPF(1 second, hint = "processing Event(stop,null)") {
            case Logging.Debug(`fsm`, s: String) if s.startsWith("processing Event(stop,null) from Actor[" + app.address + "/sys/testActor") ⇒ true
          }
          expectMsgAllOf(1 second, Logging.Debug(fsm, "canceling timer 't'"), Normal)
          expectNoMsg(1 second)
          app.mainbus.unsubscribe(testActor)
        }
      }
    }

    "fill rolling event log and hand it out" in {
      val fsmref = TestActorRef(new Actor with LoggingFSM[Int, Int] {
        override def logDepth = 3
        startWith(1, 0)
        when(1) {
          case Event("count", c) ⇒ stay using (c + 1)
          case Event("log", _)   ⇒ stay replying getLog
        }
      })
      fsmref ! "log"
      val fsm = fsmref.underlyingActor
      expectMsg(1 second, IndexedSeq(LogEntry(1, 0, "log")))
      fsmref ! "count"
      fsmref ! "log"
      expectMsg(1 second, IndexedSeq(LogEntry(1, 0, "log"), LogEntry(1, 0, "count"), LogEntry(1, 1, "log")))
      fsmref ! "count"
      fsmref ! "log"
      expectMsg(1 second, IndexedSeq(LogEntry(1, 1, "log"), LogEntry(1, 1, "count"), LogEntry(1, 2, "log")))
    }

  }

}
