/**
 * Copyright (C) 2009-2012 Typesafe Inc. <http://www.typesafe.com>
 */
package akka.docs.actor

import akka.testkit.AkkaSpec

class FSMDocSpec extends AkkaSpec {

  "simple finite state machine" must {
    //#simple-imports
    import akka.actor.{ Actor, ActorRef, FSM, Props }
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
    trait State
    case object Idle extends State
    case object Active extends State

    trait Data
    case object Uninitialized extends Data
    case class Todo(target: ActorRef, queue: Seq[Any]) extends Data
    //#simple-state
    //#simple-fsm
    class Buncher extends Actor with FSM[State, Data] {

      startWith(Idle, Uninitialized)

      when(Idle) {
        case Event(SetTarget(ref), Uninitialized) ⇒ stay using Todo(ref, Vector.empty)
      }

      //#simple-transition
      onTransition {
        case Active -> Idle ⇒
          stateData match {
            case Todo(ref, queue) ⇒ ref ! Batch(queue)
          }
      }
      //#simple-transition

      when(Active, stateTimeout = 1 second) {
        case Event(Flush | FSM.StateTimeout, t: Todo) ⇒ goto(Idle) using t.copy(queue = Vector.empty)
      }

      //#simple-unhandled
      whenUnhandled {
        // common code for both states
        case Event(Queue(obj), t @ Todo(_, v)) ⇒
          goto(Active) using t.copy(queue = v :+ obj)

        case Event(e, s) ⇒
          log.warning("received unhandled request {} in state {}/{}", e, stateName, s)
          stay
      }
      //#simple-unhandled

      initialize
    }
    //#simple-fsm

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