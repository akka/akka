/**
 * Copyright (C) 2009-2012 Typesafe Inc. <http://www.typesafe.com>
 */
package docs.actor

import language.postfixOps

import akka.testkit.{ AkkaSpec ⇒ MyFavoriteTestFrameWorkPlusAkkaTestKit }
//#test-code
import akka.actor.Props
import scala.collection.immutable

class FSMDocSpec extends MyFavoriteTestFrameWorkPlusAkkaTestKit {

  "simple finite state machine" must {
    //#fsm-code-elided
    //#simple-imports
    import akka.actor.{ Actor, ActorRef, FSM }
    import scala.concurrent.duration._
    //#simple-imports
    //#simple-events
    // received events
    case class SetTarget(ref: ActorRef)
    case class Queue(obj: Any)
    case object Flush

    // sent events
    case class Batch(obj: immutable.Seq[Any])
    //#simple-events
    //#simple-state
    // states
    sealed trait State
    case object Idle extends State
    case object Active extends State

    sealed trait Data
    case object Uninitialized extends Data
    case class Todo(target: ActorRef, queue: immutable.Seq[Any]) extends Data
    //#simple-state
    //#simple-fsm
    class Buncher extends Actor with FSM[State, Data] {

      //#fsm-body
      startWith(Idle, Uninitialized)

      //#when-syntax
      when(Idle) {
        case Event(SetTarget(ref), Uninitialized) ⇒
          stay using Todo(ref, Vector.empty)
      }
      //#when-syntax

      //#transition-elided
      onTransition {
        case Active -> Idle ⇒
          stateData match {
            case Todo(ref, queue) ⇒ ref ! Batch(queue)
          }
      }
      //#transition-elided
      //#when-syntax

      when(Active, stateTimeout = 1 second) {
        case Event(Flush | StateTimeout, t: Todo) ⇒
          goto(Idle) using t.copy(queue = Vector.empty)
      }
      //#when-syntax

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
      //#fsm-body

      initialize
    }
    //#simple-fsm
    object DemoCode {
      trait StateType
      case object SomeState extends StateType
      case object Processing extends StateType
      case object Error extends StateType
      case object Idle extends StateType
      case object Active extends StateType

      class Dummy extends Actor with FSM[StateType, Int] {
        class X
        val newData = 42
        object WillDo
        object Tick

        //#modifier-syntax
        when(SomeState) {
          case Event(msg, _) ⇒
            goto(Processing) using (newData) forMax (5 seconds) replying (WillDo)
        }
        //#modifier-syntax

        //#transition-syntax
        onTransition {
          case Idle -> Active ⇒ setTimer("timeout", Tick, 1 second, true)
          case Active -> _    ⇒ cancelTimer("timeout")
          case x -> Idle      ⇒ log.info("entering Idle from " + x)
        }
        //#transition-syntax

        //#alt-transition-syntax
        onTransition(handler _)

        def handler(from: StateType, to: StateType) {
          // handle it here ...
        }
        //#alt-transition-syntax

        //#stop-syntax
        when(Error) {
          case Event("stop", _) ⇒
            // do cleanup ...
            stop()
        }
        //#stop-syntax

        //#transform-syntax
        when(SomeState)(transform {
          case Event(bytes: Array[Byte], read) ⇒ stay using (read + bytes.length)
          case Event(bytes: List[Byte], read)  ⇒ stay using (read + bytes.size)
        } using {
          case s @ FSM.State(state, read, timeout, stopReason, replies) if read > 1000 ⇒
            goto(Processing)
        })
        //#transform-syntax

        //#alt-transform-syntax
        val processingTrigger: PartialFunction[State, State] = {
          case s @ FSM.State(state, read, timeout, stopReason, replies) if read > 1000 ⇒
            goto(Processing)
        }

        when(SomeState)(transform {
          case Event(bytes: Array[Byte], read) ⇒ stay using (read + bytes.length)
          case Event(bytes: List[Byte], read)  ⇒ stay using (read + bytes.size)
        } using processingTrigger)
        //#alt-transform-syntax

        //#termination-syntax
        onTermination {
          case StopEvent(FSM.Normal, state, data)         ⇒ // ...
          case StopEvent(FSM.Shutdown, state, data)       ⇒ // ...
          case StopEvent(FSM.Failure(cause), state, data) ⇒ // ...
        }
        //#termination-syntax

        //#unhandled-syntax
        whenUnhandled {
          case Event(x: X, data) ⇒
            log.info("Received unhandled event: " + x)
            stay
          case Event(msg, _) ⇒
            log.warning("Received unknown event: " + msg)
            goto(Error)
        }
        //#unhandled-syntax

      }

      //#logging-fsm
      import akka.actor.LoggingFSM
      class MyFSM extends Actor with LoggingFSM[StateType, Data] {
        //#body-elided
        override def logDepth = 12
        onTermination {
          case StopEvent(FSM.Failure(_), state, data) ⇒
            val lastEvents = getLog.mkString("\n\t")
            log.warning("Failure in state " + state + " with data " + data + "\n" +
              "Events leading up to this point:\n\t" + lastEvents)
        }
        // ...
        //#body-elided
      }
      //#logging-fsm

    }
    //#fsm-code-elided

    "demonstrate NullFunction" in {
      class A extends Actor with FSM[Int, Null] {
        val SomeState = 0
        //#NullFunction
        when(SomeState)(FSM.NullFunction)
        //#NullFunction
      }
    }

    "batch correctly" in {
      val buncher = system.actorOf(Props(new Buncher))
      buncher ! SetTarget(testActor)
      buncher ! Queue(42)
      buncher ! Queue(43)
      expectMsg(Batch(immutable.Seq(42, 43)))
      buncher ! Queue(44)
      buncher ! Flush
      buncher ! Queue(45)
      expectMsg(Batch(immutable.Seq(44)))
      expectMsg(Batch(immutable.Seq(45)))
    }

    "batch not if uninitialized" in {
      val buncher = system.actorOf(Props(new Buncher))
      buncher ! Queue(42)
      expectNoMsg
    }
  }
}
//#test-code
