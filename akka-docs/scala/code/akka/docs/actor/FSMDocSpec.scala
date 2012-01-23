/**
 * Copyright (C) 2009-2012 Typesafe Inc. <http://www.typesafe.com>
 */
package akka.docs.actor

//#test-code
import akka.testkit.AkkaSpec
import akka.actor.Props

class FSMDocSpec extends AkkaSpec {

  "simple finite state machine" must {
    //#fsm-code-elided
    //#simple-imports
    import akka.actor.{ Actor, ActorRef, FSM }
    import akka.util.duration._
    //#simple-imports
    //#simple-events
    // received events
    case class SetTarget(ref: ActorRef)
    case class Queue(obj: Any)
    case object Flush

    // sent events
    case class Batch(obj: Seq[Any])
    //#simple-events
    //#simple-state
    // states
    sealed trait State
    case object Idle extends State
    case object Active extends State

    sealed trait Data
    case object Uninitialized extends Data
    case class Todo(target: ActorRef, queue: Seq[Any]) extends Data
    //#simple-state
    //#simple-fsm
    class Buncher extends Actor with FSM[State, Data] {

      startWith(Idle, Uninitialized)

      when(Idle) {
        case Event(SetTarget(ref), Uninitialized) ⇒ stay using Todo(ref, Vector.empty)
      }

      //#transition-elided
      onTransition {
        case Active -> Idle ⇒
          stateData match {
            case Todo(ref, queue) ⇒ ref ! Batch(queue)
          }
      }
      //#transition-elided

      when(Active, stateTimeout = 1 second) {
        case Event(Flush | FSM.StateTimeout, t: Todo) ⇒ goto(Idle) using t.copy(queue = Vector.empty)
      }

      //#unhandled-elided
      whenUnhandled {
        // common code for both states
        case Event(Queue(obj), t @ Todo(_, v)) ⇒
          goto(Active) using t.copy(queue = v :+ obj)

        case Event(e, s) ⇒
          log.warning("received unhandled request {} in state {}/{}", e, stateName, s)
          stay
      }
      //#unhandled-elided

      initialize
    }
    //#simple-fsm
    //#fsm-code-elided

    "batch correctly" in {
      val buncher = system.actorOf(Props(new Buncher))
      buncher ! SetTarget(testActor)
      buncher ! Queue(42)
      buncher ! Queue(43)
      expectMsg(Batch(Seq(42, 43)))
      buncher ! Queue(44)
      buncher ! Flush
      buncher ! Queue(45)
      expectMsg(Batch(Seq(44)))
      expectMsg(Batch(Seq(45)))
    }

    "batch not if uninitialized" in {
      val buncher = system.actorOf(Props(new Buncher))
      buncher ! Queue(42)
      expectNoMsg
    }
  }
}
//#test-code
