/**
 * Copyright (C) 2009-2016 Lightbend Inc. <http://www.lightbend.com>
 */

package akka.actor

import language.postfixOps
import akka.testkit._
import scala.concurrent.duration._
import akka.event._
import com.typesafe.config.ConfigFactory
import scala.concurrent.Await
import akka.util.Timeout

object FSMActorSpec {

  class Latches(implicit system: ActorSystem) {
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

  case object Hello
  case object Bye

  class Lock(code: String, timeout: FiniteDuration, latches: Latches) extends Actor with FSM[LockState, CodeState] {

    import latches._

    startWith(Locked, CodeState("", code))

    when(Locked) {
      case Event(digit: Char, CodeState(soFar, code)) ⇒ {
        soFar + digit match {
          case incomplete if incomplete.length < code.length ⇒
            stay using CodeState(incomplete, code)
          case codeTry if (codeTry == code) ⇒ {
            doUnlock()
            goto(Open) using CodeState("", code) forMax timeout
          }
          case wrong ⇒ {
            stay using CodeState("", code)
          }
        }
      }
      case Event("hello", _) ⇒ stay replying "world"
      case Event("bye", _)   ⇒ stop(FSM.Shutdown)
    }

    when(Open) {
      case Event(StateTimeout, _) ⇒ {
        doLock()
        goto(Locked)
      }
    }

    whenUnhandled {
      case Event(msg, _) ⇒ {
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
      case StopEvent(FSM.Shutdown, Locked, _) ⇒
        // stop is called from lockstate with shutdown as reason...
        terminatedLatch.open()
    }

    // initialize the lock
    initialize()

    private def doLock(): Unit = lockedLatch.open()

    private def doUnlock(): Unit = unlockedLatch.open()
  }

  final case class CodeState(soFar: String, code: String)
}

@org.junit.runner.RunWith(classOf[org.scalatest.junit.JUnitRunner])
class FSMActorSpec extends AkkaSpec(Map("akka.actor.debug.fsm" -> true)) with ImplicitSender {
  import FSMActorSpec._

  val timeout = Timeout(2 seconds)

  "An FSM Actor" must {

    "unlock the lock" in {

      import FSM.{ Transition, CurrentState, SubscribeTransitionCallBack }

      val latches = new Latches
      import latches._

      // lock that locked after being open for 1 sec
      val lock = system.actorOf(Props(new Lock("33221", 1 second, latches)))

      val transitionTester = system.actorOf(Props(new Actor {
        def receive = {
          case Transition(_, _, _)                          ⇒ transitionCallBackLatch.open
          case CurrentState(_, s: LockState) if s eq Locked ⇒ initialStateLatch.open // SI-5900 workaround
        }
      }))

      lock ! SubscribeTransitionCallBack(transitionTester)
      Await.ready(initialStateLatch, timeout.duration)

      lock ! '3'
      lock ! '3'
      lock ! '2'
      lock ! '2'
      lock ! '1'

      Await.ready(unlockedLatch, timeout.duration)
      Await.ready(transitionLatch, timeout.duration)
      Await.ready(transitionCallBackLatch, timeout.duration)
      Await.ready(lockedLatch, timeout.duration)

      EventFilter.warning(start = "unhandled event", occurrences = 1) intercept {
        lock ! "not_handled"
        Await.ready(unhandledLatch, timeout.duration)
      }

      val answerLatch = TestLatch()
      val tester = system.actorOf(Props(new Actor {
        def receive = {
          case Hello   ⇒ lock ! "hello"
          case "world" ⇒ answerLatch.open
          case Bye     ⇒ lock ! "bye"
        }
      }))
      tester ! Hello
      Await.ready(answerLatch, timeout.duration)

      tester ! Bye
      Await.ready(terminatedLatch, timeout.duration)
    }

    "log termination" in {
      val fsm = TestActorRef(new Actor with FSM[Int, Null] {
        startWith(1, null)
        when(1) {
          case Event("go", _) ⇒ goto(2)
        }
      })
      val name = fsm.path.toString
      EventFilter.error("Next state 2 does not exist", occurrences = 1) intercept {
        system.eventStream.subscribe(testActor, classOf[Logging.Error])
        fsm ! "go"
        expectMsgPF(1 second, hint = "Next state 2 does not exist") {
          case Logging.Error(_, `name`, _, "Next state 2 does not exist") ⇒ true
        }
        system.eventStream.unsubscribe(testActor)
      }
    }

    "run onTermination upon ActorRef.stop()" in {
      val started = TestLatch(1)
      /*
       * This lazy val trick is beyond evil: KIDS, DON'T TRY THIS AT HOME!
       * It is necessary here because of the path-dependent type fsm.StopEvent.
       */
      lazy val fsm = new Actor with FSM[Int, Null] {
        override def preStart = { started.countDown }
        startWith(1, null)
        when(1) { FSM.NullFunction }
        onTermination {
          case x ⇒ testActor ! x
        }
      }
      val ref = system.actorOf(Props(fsm))
      Await.ready(started, timeout.duration)
      system.stop(ref)
      expectMsg(1 second, fsm.StopEvent(FSM.Shutdown, 1, null))
    }

    "run onTermination with updated state upon stop(reason, stateData)" in {
      val expected = "pigdog"
      val actor = system.actorOf(Props(new Actor with FSM[Int, String] {
        startWith(1, null)
        when(1) {
          case Event(2, null) ⇒ stop(FSM.Normal, expected)
        }
        onTermination {
          case StopEvent(FSM.Normal, 1, `expected`) ⇒ testActor ! "green"
        }
      }))
      actor ! 2
      expectMsg("green")
    }

    "cancel all timers when terminated" in {
      val timerNames = List("timer-1", "timer-2", "timer-3")

      // Lazy so fsmref can refer to checkTimersActive
      lazy val fsmref = TestFSMRef(new Actor with FSM[String, Null] {
        startWith("not-started", null)
        when("not-started") {
          case Event("start", _) ⇒ goto("started") replying "starting"
        }
        when("started", stateTimeout = 10 seconds) {
          case Event("stop", _) ⇒ stop()
        }
        onTransition {
          case "not-started" -> "started" ⇒
            for (timerName ← timerNames) setTimer(timerName, (), 10 seconds, false)
        }
        onTermination {
          case _ ⇒ {
            checkTimersActive(false)
            testActor ! "stopped"
          }
        }
      })

      def checkTimersActive(active: Boolean) {
        for (timer ← timerNames) fsmref.isTimerActive(timer) should ===(active)
        fsmref.isStateTimerActive should ===(active)
      }

      checkTimersActive(false)

      fsmref ! "start"
      expectMsg(1 second, "starting")
      checkTimersActive(true)

      fsmref ! "stop"
      expectMsg(1 second, "stopped")
    }

    "log events and transitions if asked to do so" in {
      import scala.collection.JavaConverters._
      val config = ConfigFactory.parseMap(Map("akka.loglevel" -> "DEBUG", "akka.actor.serialize-messages" -> "off",
        "akka.actor.debug.fsm" -> true).asJava).withFallback(system.settings.config)
      val fsmEventSystem = ActorSystem("fsmEvent", config)
      try {
        new TestKit(fsmEventSystem) {
          EventFilter.debug(occurrences = 5) intercept {
            val fsm = TestActorRef(new Actor with LoggingFSM[Int, Null] {
              startWith(1, null)
              when(1) {
                case Event("go", _) ⇒
                  setTimer("t", FSM.Shutdown, 1.5 seconds, false)
                  goto(2)
              }
              when(2) {
                case Event("stop", _) ⇒
                  cancelTimer("t")
                  stop
              }
              onTermination {
                case StopEvent(r, _, _) ⇒ testActor ! r
              }
            })
            val name = fsm.path.toString
            val fsmClass = fsm.underlyingActor.getClass
            system.eventStream.subscribe(testActor, classOf[Logging.Debug])
            fsm ! "go"
            expectMsgPF(1 second, hint = "processing Event(go,null)") {
              case Logging.Debug(`name`, `fsmClass`, s: String) if s.startsWith("processing Event(go,null) from Actor[") ⇒ true
            }
            expectMsg(1 second, Logging.Debug(name, fsmClass, "setting timer 't'/1500 milliseconds: Shutdown"))
            expectMsg(1 second, Logging.Debug(name, fsmClass, "transition 1 -> 2"))
            fsm ! "stop"
            expectMsgPF(1 second, hint = "processing Event(stop,null)") {
              case Logging.Debug(`name`, `fsmClass`, s: String) if s.startsWith("processing Event(stop,null) from Actor[") ⇒ true
            }
            expectMsgAllOf(1 second, Logging.Debug(name, fsmClass, "canceling timer 't'"), FSM.Normal)
            expectNoMsg(1 second)
            system.eventStream.unsubscribe(testActor)
          }
        }
      } finally {
        TestKit.shutdownActorSystem(fsmEventSystem)
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
      import FSM.LogEntry
      expectMsg(1 second, IndexedSeq(LogEntry(1, 0, "log")))
      fsmref ! "count"
      fsmref ! "log"
      expectMsg(1 second, IndexedSeq(LogEntry(1, 0, "log"), LogEntry(1, 0, "count"), LogEntry(1, 1, "log")))
      fsmref ! "count"
      fsmref ! "log"
      expectMsg(1 second, IndexedSeq(LogEntry(1, 1, "log"), LogEntry(1, 1, "count"), LogEntry(1, 2, "log")))
    }

    "allow transforming of state results" in {
      import akka.actor.FSM._
      val fsmref = system.actorOf(Props(new Actor with FSM[Int, Int] {
        startWith(0, 0)
        when(0)(transform {
          case Event("go", _) ⇒ stay
        } using {
          case x ⇒ goto(1)
        })
        when(1) {
          case _ ⇒ stay
        }
      }))
      fsmref ! SubscribeTransitionCallBack(testActor)
      fsmref ! "go"
      expectMsg(CurrentState(fsmref, 0))
      expectMsg(Transition(fsmref, 0, 1))
    }

    "allow cancelling stateTimeout by issuing forMax(Duration.Inf)" in {
      val sys = ActorSystem("fsmEvent")
      val p = TestProbe()(sys)

      val OverrideTimeoutToInf = "override-timeout-to-inf"

      val fsm = sys.actorOf(Props(new Actor with FSM[String, String] {

        startWith("init", "")

        when("init", stateTimeout = 1.second) {
          case Event(StateTimeout, _) ⇒
            p.ref ! StateTimeout
            stay()

          case Event(OverrideTimeoutToInf, _) ⇒
            p.ref ! OverrideTimeoutToInf
            stay() forMax Duration.Inf
        }

        initialize()
      }))

      try {
        p.expectMsg(FSM.StateTimeout)

        fsm ! OverrideTimeoutToInf
        p.expectMsg(OverrideTimeoutToInf)
        p.expectNoMsg(3.seconds)
      } finally {
        TestKit.shutdownActorSystem(sys)
      }
    }

  }

}
