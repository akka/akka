FSM
===

Mo﻿dule stability: **STABLE**

The FSM (Finite State Machine) is available as a mixin for the akka Actor and is best described in the `Erlang design principals <@http://www.erlang.org/documentation/doc-4.8.2/doc/design_principles/fsm.html>`_

A FSM can be described as a set of relations of the form:
> **State(S) x Event(E) -> Actions (A), State(S')**

These relations are interpreted as meaning:
> *If we are in state S and the event E occurs, we should perform the actions A and make a transition to the state S'.*

State Definitions
-----------------

To demonstrate the usage of states we start with a simple state only FSM without state data. The state can be of any type so for this example we create the states A, B and C.

.. code-block:: scala

  sealed trait ExampleState
  case object A extends ExampleState
  case object B extends ExampleState
  case object C extends ExampleState

Now lets create an object to influence the FSM and define the states and their behaviour.

.. code-block:: scala

  import akka.actor.{Actor, FSM}
  import FSM._
  import akka.util.duration._

  case object Move

  class ABC extends Actor with FSM[ExampleState,Unit] {

    startWith(A, Unit)

    when(A) {
      case Event(Move, _) =>
        log.info("Go to B and move on after 5 seconds")
        goto(B) forMax (5 seconds)
    }

    when(B) {
      case Event(StateTimeout, _) =>
        log.info("Moving to C")
        goto(C)
    }

    when(C) {
      case Event(Move, _) =>
        log.info("Stopping")
        stop
    }

    initialize // this checks validity of the initial state and sets up timeout if needed
  }

So we use 'when' to specify a state and define what needs to happen when we receive an event. We use 'goto' to go to another state. We use 'forMax' to tell for how long we maximum want to stay in that state before we receive a timeout notification. We use 'stop' to stop the FSM. And we use 'startWith' to specify which state to start with. The call to 'initialize' should be the last action done in the actor constructor.

If we want to stay in the current state we can use (I'm hoping you can guess this by now) 'stay'. That can also be combined with the 'forMax'

.. code-block:: scala

  when(C) {
    case Event(unknown, _) =>
      stay forMax (2 seconds)
  }

The timeout can also be associated with the state itself, the choice depends on whether most of the transitions to the state require the same value for the timeout:

.. code-block:: scala

  when(A) {
    case Ev(Start(msg)) => // convenience extractor when state data not needed
      goto(Timer) using msg
  }

  when(B, stateTimeout = 12 seconds) {
    case Event(StateTimeout, msg) =>
      target ! msg
    case Ev(DifferentPause(dur : Duration)) =>
      stay forMax dur // overrides default state timeout for this single transition
  }

Unhandled Events
----------------

If a state doesn't handle a received event a warning is logged. If you want to do something with this events you can specify that with 'whenUnhandled'

.. code-block:: scala

  whenUnhandled {
    case Event(x, _) => log.info("Received unhandled event: " + x)
  }

Termination
-----------

You can use 'onTermination' to specify custom code that is executed when the FSM is stopped. A reason is passed to tell how the FSM was stopped.

.. code-block:: scala

  onTermination {
    case Normal => log.info("Stopped normal")
    case Shutdown => log.info("Stopped because of shutdown")
    case Failure(cause) => log.error("Stopped because of failure: " + cause)
  }

State Transitions
-----------------

When state transitions to another state we might want to know about this and take action. To specify this we can use 'onTransition' to capture the transitions.

.. code-block:: scala

  onTransition {
    case A -> B => log.info("Moving from A to B")
    case _ -> C => log.info("Moving from something to C")
  }

Multiple onTransition blocks may be given and all will be execution while processing a transition. This enables you to associate your Actions either with the initial state of a processing step, or with the transition into the final state of a processing step.

Transitions occur "between states" conceptually, which means after any actions you have put into the event handling block; this is obvious since the next state is only defined by the value returned by the event handling logic. You do not need to worry about the exact order with respect to setting the internal state variable, as everything within the FSM actor is running single-threaded anyway.

It is also possible to pass a function object accepting two states to onTransition, in case your state handling logic is implemented as a method:

.. code-block:: scala

  onTransition(handler _)

  private def handler(from: State, to: State) {
    ...
  }

State Data
----------

The FSM can also hold state data that is attached to every event. The state data can be of any type but to demonstrate let's look at a lock with a String as state data holding the entered unlock code.
First we need two states for the lock:

.. code-block:: scala

  sealed trait LockState
  case object Locked extends LockState
  case object Open extends LockState

Now we can create a lock FSM that takes LockState as a state and a String as state data:

.. code-block:: scala

  import akka.actor.{FSM, Actor}
  import FSM._
  import akka.util.duration._

  class Lock(code: String) extends Actor with FSM[LockState, String] {

    val emptyCode = ""

    when(Locked) {
      // receive a digit and the code that we have so far
      case Event(digit: Char, soFar) => {
        // add the digit to what we have
        soFar + digit match {
          // not enough digits yet so stay using the incomplete code as the new state data
          case incomplete if incomplete.length < code.length =>
            stay using incomplete
          // code matched the one from the lock so go to Open state and reset the state data
          case `code` =>
            log.info("Unlocked")
            goto(Open) using emptyCode forMax (1 seconds)
          // wrong code, stay Locked and reset the state data
          case wrong =>
            log.error("Wrong code " + wrong)
            stay using emptyCode
        }
      }
    }

    when(Open) {
      // after the timeout, go back to Locked state
      case Event(StateTimeout, _) => {
        log.info("Locked")
        goto(Locked)
      }
    }

    startWith(Locked, emptyCode)
  }

To use the Lock you can run a small program like this:

.. code-block:: scala

  object Lock {

    def main(args: Array[String]) {

      val lock = Actor.actorOf(new Lock("1234")).start()

      lock ! '1'
      lock ! '2'
      lock ! '3'
      lock ! '4'

      Actor.registry.shutdownAll()
      exit
    }
  }

Dining Hakkers
--------------

A bigger FSM example can be found in the sources.
`Dining Hakkers using FSM <@https://github.com/jboner/akka/blob/master/akka-samples/akka-sample-fsm/src/main/scala/DiningHakkersOnFsm.scala#L1>`_
`Dining Hakkers using become <@https://github.com/jboner/akka/blob/master/akka-samples/akka-sample-fsm/src/main/scala/DiningHakkersOnBecome.scala#L1>`_
