package akka.docs.testkit

//#imports-test-probe
import akka.testkit.TestProbe
import akka.actor.Actor
import akka.actor.ActorRef
import akka.actor.Props
import akka.util.duration._

//#imports-test-probe

import akka.testkit.AkkaSpec
import akka.actor.Actor
import akka.testkit.DefaultTimeout
import akka.testkit.ImplicitSender
import akka.actor.ActorRef
import akka.actor.Props

object TestkitDocSpec {
  case object Say42
  case object Unknown

  class MyActor extends Actor {
    def receive = {
      case Say42       ⇒ sender ! 42
      case "some work" ⇒ sender ! "some result"
    }
  }

  //#my-double-echo
  class MyDoubleEcho extends Actor {
    var dest1: ActorRef = _
    var dest2: ActorRef = _
    def receive = {
      case (d1: ActorRef, d2: ActorRef) ⇒
        dest1 = d1
        dest2 = d2
      case x ⇒
        dest1 ! x
        dest2 ! x
    }
  }

  //#my-double-echo

  import akka.testkit.TestProbe

  //#test-probe-forward-actors
  class Source(target: ActorRef) extends Actor {
    def receive = {
      case "start" ⇒ target ! "work"
    }
  }

  class Destination extends Actor {
    def receive = {
      case x ⇒ // Do something..
    }
  }

  //#test-probe-forward-actors

  class LoggingActor extends Actor {
    //#logging-receive
    import akka.event.LoggingReceive
    implicit def system = context.system
    def receive = LoggingReceive(this) {
      case msg ⇒ // Do something...
    }
    //#logging-receive
  }
}

class TestkitDocSpec extends AkkaSpec with DefaultTimeout with ImplicitSender {
  import TestkitDocSpec._

  "demonstrate usage of TestActorRef" in {
    //#test-actor-ref
    import akka.testkit.TestActorRef

    val actorRef = TestActorRef[MyActor]
    val actor = actorRef.underlyingActor
    //#test-actor-ref
  }

  "demonstrate usage of TestFSMRef" in {
    //#test-fsm-ref
    import akka.testkit.TestFSMRef
    import akka.actor.FSM
    import akka.util.duration._

    val fsm = TestFSMRef(new Actor with FSM[Int, String] {
      startWith(1, "")
      when(1) {
        case Ev("go") ⇒ goto(2) using "go"
      }
      when(2) {
        case Ev("back") ⇒ goto(1) using "back"
      }
    })

    assert(fsm.stateName == 1)
    assert(fsm.stateData == "")
    fsm ! "go" // being a TestActorRef, this runs also on the CallingThreadDispatcher
    assert(fsm.stateName == 2)
    assert(fsm.stateData == "go")

    fsm.setState(stateName = 1)
    assert(fsm.stateName == 1)

    assert(fsm.timerActive_?("test") == false)
    fsm.setTimer("test", 12, 10 millis, true)
    assert(fsm.timerActive_?("test") == true)
    fsm.cancelTimer("test")
    assert(fsm.timerActive_?("test") == false)
    //#test-fsm-ref
  }

  "demonstrate testing of behavior" in {

    //#test-behavior
    import akka.testkit.TestActorRef
    import akka.util.duration._
    import akka.dispatch.Await

    val actorRef = TestActorRef(new MyActor)
    // hypothetical message stimulating a '42' answer
    val result = Await.result((actorRef ? Say42), 5 seconds).asInstanceOf[Int]
    result must be(42)
    //#test-behavior
  }

  "demonstrate unhandled message" in {
    //#test-unhandled
    import akka.testkit.TestActorRef
    import akka.actor.UnhandledMessageException

    val ref = TestActorRef[MyActor]
    intercept[UnhandledMessageException] { ref(Unknown) }
    //#test-unhandled
  }

  "demonstrate expecting exceptions" in {
    //#test-expecting-exceptions
    import akka.testkit.TestActorRef

    val actorRef = TestActorRef(new Actor {
      def receive = {
        case boom ⇒ throw new IllegalArgumentException("boom")
      }
    })
    intercept[IllegalArgumentException] { actorRef("hello") }
    //#test-expecting-exceptions
  }

  "demonstrate within" in {
    type Worker = MyActor
    //#test-within
    import akka.actor.Props
    import akka.util.duration._

    val worker = system.actorOf(Props[Worker])
    within(200 millis) {
      worker ! "some work"
      expectMsg("some result")
      expectNoMsg // will block for the rest of the 200ms
      Thread.sleep(300) // will NOT make this block fail
    }
    //#test-within
  }

  "demonstrate dilated duration" in {
    //#duration-dilation
    import akka.util.duration._
    import akka.testkit._
    10.milliseconds.dilated
    //#duration-dilation
  }

  "demonstrate usage of probe" in {
    //#test-probe
    val probe1 = TestProbe()
    val probe2 = TestProbe()
    val actor = system.actorOf(Props[MyDoubleEcho])
    actor ! (probe1.ref, probe2.ref)
    actor ! "hello"
    probe1.expectMsg(50 millis, "hello")
    probe2.expectMsg(50 millis, "hello")
    //#test-probe

    //#test-special-probe
    case class Update(id: Int, value: String)

    val probe = new TestProbe(system) {
      def expectUpdate(x: Int) = {
        expectMsgPF() {
          case Update(id, _) if id == x ⇒ true
        }
        sender ! "ACK"
      }
    }
    //#test-special-probe
  }

  "demonstrate probe reply" in {
    import akka.testkit.TestProbe
    import akka.util.duration._
    //#test-probe-reply
    val probe = TestProbe()
    val future = probe.ref ? "hello"
    probe.expectMsg(0 millis, "hello") // TestActor runs on CallingThreadDispatcher
    probe.sender ! "world"
    assert(future.isCompleted && future.value == Some(Right("world")))
    //#test-probe-reply
  }

  "demonstrate probe forward" in {
    import akka.testkit.TestProbe
    import akka.actor.Props
    //#test-probe-forward
    val probe = TestProbe()
    val source = system.actorOf(Props(new Source(probe.ref)))
    val dest = system.actorOf(Props[Destination])
    source ! "start"
    probe.expectMsg("work")
    probe.forward(dest)
    //#test-probe-forward
  }

  "demonstrate " in {
    //#calling-thread-dispatcher
    import akka.testkit.CallingThreadDispatcher
    val dispatcher = new CallingThreadDispatcher(system.dispatcherFactory.prerequisites)
    val ref = system.actorOf(Props[MyActor].withDispatcher(dispatcher))
    //#calling-thread-dispatcher
  }

}
