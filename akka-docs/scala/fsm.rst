.. _fsm:

###
FSM
###

.. sidebar:: Contents

   .. contents:: :local:

Overview
========

The FSM (Finite State Machine) is available as a mixin for the akka Actor and
is best described in the `Erlang design principles
<http://www.erlang.org/documentation/doc-4.8.2/doc/design_principles/fsm.html>`_

A FSM can be described as a set of relations of the form:

  **State(S) x Event(E) -> Actions (A), State(S')**

These relations are interpreted as meaning:

  *If we are in state S and the event E occurs, we should perform the actions A and make a transition to the state S'.*

A Simple Example
================

To demonstrate the usage of states we start with a simple FSM without state
data. The state can be of any type so for this example we create the states A,
B and C.

.. code-block:: scala

  sealed trait ExampleState
  case object A extends ExampleState
  case object B extends ExampleState
  case object C extends ExampleState

Now lets create an object representing the FSM and defining the behavior.

.. code-block:: scala

  import akka.actor.{Actor, FSM}
  import akka.util.duration._

  case object Move

  class ABC extends Actor with FSM[ExampleState, Unit] {

    import FSM._

    startWith(A, Unit)

    when(A) {
      case Ev(Move) =>
        log.info(this, "Go to B and move on after 5 seconds")
        goto(B) forMax (5 seconds)
    }

    when(B) {
      case Ev(StateTimeout) =>
        log.info(this, "Moving to C")
        goto(C)
    }

    when(C) {
      case Ev(Move) =>
        log.info(this, "Stopping")
        stop
    }

    initialize // this checks validity of the initial state and sets up timeout if needed
  }

Each state is described by one or more :func:`when(state)` blocks; if more than
one is given for the same state, they are tried in the order given until the
first is found which matches the incoming event. Events are matched using
either :func:`Ev(msg)` (if no state data are to be extracted) or
:func:`Event(msg, data)`, see below. The statements for each case are the
actions to be taken, where the final expression must describe the transition
into the next state. This can either be :func:`stay` when no transition is
needed or :func:`goto(target)` for changing into the target state. The
transition may be annotated with additional properties, where this example
includes a state timeout of 5 seconds after the transition into state B:
:func:`forMax(duration)` arranges for a :obj:`StateTimeout` message to be
scheduled, unless some other message is received first. The construction of the
FSM is finished by calling the :func:`initialize` method as last part of the
ABC constructor.

State Data
==========

The FSM can also hold state data associated with the internal state of the
state machine. The state data can be of any type but to demonstrate let's look
at a lock with a :class:`String` as state data holding the entered unlock code.
First we need two states for the lock:

.. code-block:: scala

  sealed trait LockState
  case object Locked extends LockState
  case object Open extends LockState

Now we can create a lock FSM that takes :class:`LockState` as a state and a
:class:`String` as state data:

.. code-block:: scala

  import akka.actor.{Actor, FSM}

  class Lock(code: String) extends Actor with FSM[LockState, String] {

    import FSM._

    val emptyCode = ""

    startWith(Locked, emptyCode)

    when(Locked) {
      // receive a digit and the code that we have so far
      case Event(digit: Char, soFar) => {
        // add the digit to what we have
        soFar + digit match {
          case incomplete if incomplete.length < code.length =>
            // not enough digits yet so stay using the
            // incomplete code as the new state data
            stay using incomplete
          case `code` =>
            // code matched the one from the lock
            // so go to Open state and reset the state data
            goto(Open) using emptyCode forMax (1 seconds)
          case wrong =>
            // wrong code, stay Locked and reset the state data
            stay using emptyCode
        }
      }
    }

    when(Open) {
      case Ev(StateTimeout, _) => {
        // after the timeout, go back to Locked state
        goto(Locked)
      }
    }

    initialize
  }

This very simple example shows how the complete state of the FSM is encoded in
the :obj:`(State, Data)` pair and only explicitly updated during transitions.
This encapsulation is what makes state machines a powerful abstraction, e.g.
for handling socket states in a network server application.

Reference
=========

This section describes the DSL in a more formal way, refer to `Examples`_ for more sample material.

The FSM Trait and Object
------------------------

The :class:`FSM` trait may only be mixed into an :class:`Actor`. Instead of
extending :class:`Actor`, the self type approach was chosen in order to make it
obvious that an actor is actually created.  Importing all members of the
:obj:`FSM` object is recommended to receive useful implicits and directly
access the symbols like :obj:`StateTimeout`. This import is usually placed
inside the state machine definition:

.. code-block:: scala

   class MyFSM extends Actor with FSM[State, Data] {
     import FSM._

     ...

   }

The :class:`FSM` trait takes two type parameters:

 #. the supertype of all state names, usually a sealed trait with case objects
    extending it,
 #. the type of the state data which are tracked by the :class:`FSM` module
    itself.

.. _fsm-philosophy:

.. note::

   The state data together with the state name describe the internal state of
   the state machine; if you stick to this scheme and do not add mutable fields
   to the FSM class you have the advantage of making all changes of the
   internal state explicit in a few well-known places.

Defining Timeouts
-----------------

The :class:`FSM` module uses :ref:`Duration` for all timing configuration.
Several methods, like :func:`when()` and :func:`startWith()` take a
:class:`FSM.Timeout`, which is an alias for :class:`Option[Duration]`. There is
an implicit conversion available in the :obj:`FSM` object which makes this
transparent, just import it into your FSM body.

Defining States
---------------

A state is defined by one or more invocations of the method

  :func:`when(<name>[, stateTimeout = <timeout>])(stateFunction)`.
  
The given name must be an object which is type-compatible with the first type
parameter given to the :class:`FSM` trait. This object is used as a hash key,
so you must ensure that it properly implements :meth:`equals` and
:meth:`hashCode`; in particular it must not be mutable. The easiest fit for
these requirements are case objects.

If the :meth:`stateTimeout` parameter is given, then all transitions into this
state, including staying, receive this timeout by default. Initiating the
transition with an explicit timeout may be used to override this default, see
`Initiating Transitions`_ for more information. The state timeout of any state
may be changed during action processing with :func:`setStateTimeout(state,
duration)`. This enables runtime configuration e.g. via external message.

The :meth:`stateFunction` argument is a :class:`PartialFunction[Event, State]`,
which is conveniently given using the partial function literal syntax as
demonstrated below:

.. code-block:: scala

  when(Idle) {
    case Ev(Start(msg)) => // convenience extractor when state data not needed
      goto(Timer) using (msg, sender)
  }

  when(Timer, stateTimeout = 12 seconds) {
    case Event(StateTimeout, (msg, sender)) =>
      sender ! msg
      goto(Idle)
  }

The :class:`Event(msg, data)` case class may be used directly in the pattern as
shown in state Idle, or you may use the extractor :obj:`Ev(msg)` when the state
data are not needed.

Defining the Initial State
--------------------------

Each FSM needs a starting point, which is declared using

  :func:`startWith(state, data[, timeout])`

The optionally given timeout argument overrides any specification given for the
desired initial state. If you want to cancel a default timeout, use
:obj:`Duration.Inf`.

Unhandled Events
----------------

If a state doesn't handle a received event a warning is logged. If you want to
do something else in this case you can specify that with
:func:`whenUnhandled(stateFunction)`:

.. code-block:: scala

  whenUnhandled {
    case Event(x : X, data) =>
      log.info(this, "Received unhandled event: " + x)
      stay
    case Ev(msg) =>
      log.warn(this, "Received unknown event: " + x)
      goto(Error)
  }

**IMPORTANT**: This handler is not stacked, meaning that each invocation of
:func:`whenUnhandled` replaces the previously installed handler.

Initiating Transitions
----------------------

The result of any :obj:`stateFunction` must be a definition of the next state
unless terminating the FSM, which is described in `Termination from Inside`_.
The state definition can either be the current state, as described by the
:func:`stay` directive, or it is a different state as given by
:func:`goto(state)`. The resulting object allows further qualification by way
of the modifiers described in the following:

:meth:`forMax(duration)`
  This modifier sets a state timeout on the next state. This means that a timer
  is started which upon expiry sends a :obj:`StateTimeout` message to the FSM.
  This timer is canceled upon reception of any other message in the meantime;
  you can rely on the fact that the :obj:`StateTimeout` message will not be
  processed after an intervening message.

  This modifier can also be used to override any default timeout which is
  specified for the target state. If you want to cancel the default timeout,
  use :obj:`Duration.Inf`.

:meth:`using(data)`
  This modifier replaces the old state data with the new data given. If you
  follow the advice :ref:`above <fsm-philosophy>`, this is the only place where
  internal state data are ever modified.

:meth:`replying(msg)`
  This modifier sends a reply to the currently processed message and otherwise
  does not modify the state transition.

All modifier can be chained to achieve a nice and concise description:

.. code-block:: scala

  when(State) {
    case Ev(msg) =>
      goto(Processing) using (msg) forMax (5 seconds) replying (WillDo)
  }

The parentheses are not actually needed in all cases, but they visually
distinguish between modifiers and their arguments and therefore make the code
even more pleasant to read for foreigners.

.. note::

   Please note that the ``return`` statement may not be used in :meth:`when`
   blocks or similar; this is a Scala restriction. Either refactor your code
   using ``if () ... else ...`` or move it into a method definition.

Monitoring Transitions
----------------------

Transitions occur "between states" conceptually, which means after any actions
you have put into the event handling block; this is obvious since the next
state is only defined by the value returned by the event handling logic. You do
not need to worry about the exact order with respect to setting the internal
state variable, as everything within the FSM actor is running single-threaded
anyway.

Internal Monitoring
^^^^^^^^^^^^^^^^^^^

Up to this point, the FSM DSL has been centered on states and events. The dual
view is to describe it as a series of transitions. This is enabled by the
method

  :func:`onTransition(handler)`

which associates actions with a transition instead of with a state and event.
The handler is a partial function which takes a pair of states as input; no
resulting state is needed as it is not possible to modify the transition in
progress.

.. code-block:: scala

   onTransition {
     case Idle -> Active => setTimer("timeout")
     case Active -> _ => cancelTimer("timeout")
     case x -> Idle => log.info("entering Idle from "+x)
   }

The convenience extractor :obj:`->` enables decomposition of the pair of states
with a clear visual reminder of the transition's direction. As usual in pattern
matches, an underscore may be used for irrelevant parts; alternatively you
could bind the unconstrained state to a variable, e.g. for logging as shown in
the last case.

It is also possible to pass a function object accepting two states to
:func:`onTransition`, in case your transition handling logic is implemented as
a method:

.. code-block:: scala

  onTransition(handler _)

  private def handler(from: State, to: State) {
    ...
  }

The handlers registered with this method are stacked, so you can intersperse
:func:`onTransition` blocks with :func:`when` blocks as suits your design. It
should be noted, however, that *all handlers will be invoked for each
transition*, not only the first matching one. This is designed specifically so
you can put all transition handling for a certain aspect into one place without
having to worry about earlier declarations shadowing later ones; the actions
are still executed in declaration order, though.

.. note::

   This kind of internal monitoring may be used to structure your FSM according
   to transitions, so that for example the cancellation of a timer upon leaving
   a certain state cannot be forgot when adding new target states.

External Monitoring
^^^^^^^^^^^^^^^^^^^

External actors may be registered to be notified of state transitions by
sending a message :class:`SubscribeTransitionCallBack(actorRef)`. The named
actor will be sent a :class:`CurrentState(self, stateName)` message immediately
and will receive :class:`Transition(actorRef, oldState, newState)` messages
whenever a new state is reached. External monitors may be unregistered by
sending :class:`UnsubscribeTransitionCallBack(actorRef)` to the FSM actor.

Registering a not-running listener generates a warning and fails gracefully.
Stopping a listener without unregistering will remove the listener from the
subscription list upon the next transition.

Timers
------

Besides state timeouts, FSM manages timers identified by :class:`String` names.
You may set a timer using

  :func:`setTimer(name, msg, interval, repeat)`

where :obj:`msg` is the message object which will be sent after the duration
:obj:`interval` has elapsed. If :obj:`repeat` is :obj:`true`, then the timer is
scheduled at fixed rate given by the :obj:`interval` parameter. Timers may be
canceled using

  :func:`cancelTimer(name)`

which is guaranteed to work immediately, meaning that the scheduled message
will not be processed after this call even if the timer already fired and
queued it. The status of any timer may be inquired with

  :func:`timerActive_?(name)`

These named timers complement state timeouts because they are not affected by
intervening reception of other messages.

Termination from Inside
-----------------------

The FSM is stopped by specifying the result state as

  :func:`stop([reason[, data]])`

The reason must be one of :obj:`Normal` (which is the default), :obj:`Shutdown`
or :obj:`Failure(reason)`, and the second argument may be given to change the
state data which is available during termination handling.

.. note::

   It should be noted that :func:`stop` does not abort the actions and stop the
   FSM immediately. The stop action must be returned from the event handler in
   the same way as a state transition (but note that the ``return`` statement
   may not be used within a :meth:`when` block).

.. code-block:: scala

   when(A) {
     case Ev(Stop) =>
       doCleanup()
       stop()
   }

You can use :func:`onTermination(handler)` to specify custom code that is
executed when the FSM is stopped. The handler is a partial function which takes
a :class:`StopEvent(reason, stateName, stateData)` as argument:

.. code-block:: scala

  onTermination {
    case StopEvent(Normal, s, d)         => ...
    case StopEvent(Shutdown, _, _)       => ...
    case StopEvent(Failure(cause), s, d) => ...
  }

As for the :func:`whenUnhandled` case, this handler is not stacked, so each
invocation of :func:`onTermination` replaces the previously installed handler.

Termination from Outside
------------------------

When an :class:`ActorRef` associated to a FSM is stopped using the
:meth:`stop()` method, its :meth:`postStop` hook will be executed. The default
implementation by the :class:`FSM` trait is to execute the
:meth:`onTermination` handler if that is prepared to handle a
:obj:`StopEvent(Shutdown, ...)`.

.. warning::

  In case you override :meth:`postStop` and want to have your
  :meth:`onTermination` handler called, do not forget to call
  ``super.postStop``.

Testing and Debugging Finite State Machines
===========================================

During development and for trouble shooting FSMs need care just as any other
actor. There are specialized tools available as described in :ref:`TestFSMRef`
and in the following.

Event Tracing
-------------

The setting ``akka.actor.debug.fsm`` in `:ref:`configuration` enables logging of an
event trace by :class:`LoggingFSM` instances::

  class MyFSM extends Actor with LoggingFSM[X, Z] {
    ...
  }

This FSM will log at DEBUG level:

  * all processed events, including :obj:`StateTimeout` and scheduled timer
    messages
  * every setting and cancellation of named timers
  * all state transitions

Life cycle changes and special messages can be logged as described for
:ref:`Actors <actor.logging>`.

Rolling Event Log
-----------------

The :class:`LoggingFSM` trait adds one more feature to the FSM: a rolling event
log which may be used during debugging (for tracing how the FSM entered a
certain failure state) or for other creative uses::

  class MyFSM extends Actor with LoggingFSM[X, Z] {
    override def logDepth = 12
    onTermination {
      case StopEvent(Failure(_), state, data) =>
        log.warning(this, "Failure in state "+state+" with data "+data+"\n"+
          "Events leading up to this point:\n\t"+getLog.mkString("\n\t"))
    }
    ...
  }

The :meth:`logDepth` defaults to zero, which turns off the event log.

.. warning::

  The log buffer is allocated during actor creation, which is why the
  configuration is done using a virtual method call. If you want to override
  with a ``val``, make sure that its initialization happens before the
  initializer of :class:`LoggingFSM` runs, and do not change the value returned
  by ``logDepth`` after the buffer has been allocated.

The contents of the event log are available using method :meth:`getLog`, which
returns an :class:`IndexedSeq[LogEntry]` where the oldest entry is at index
zero.

Examples
========

A bigger FSM example contrasted with Actor's :meth:`become`/:meth:`unbecome` can be found in the sources:

 * `Dining Hakkers using FSM <https://github.com/jboner/akka/blob/master/akka-samples/akka-sample-fsm/src/main/scala/DiningHakkersOnFsm.scala#L1>`_
 * `Dining Hakkers using become <https://github.com/jboner/akka/blob/master/akka-samples/akka-sample-fsm/src/main/scala/DiningHakkersOnBecome.scala#L1>`_
