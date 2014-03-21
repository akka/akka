.. _lambda-fsm:

################################
 FSM (Java with Lambda Support)
################################


Overview
========

The FSM (Finite State Machine) is available as an abstract base class that implements
an akka Actor and is best described in the `Erlang design principles
<http://www.erlang.org/documentation/doc-4.8.2/doc/design_principles/fsm.html>`_

A FSM can be described as a set of relations of the form:

  **State(S) x Event(E) -> Actions (A), State(S')**

These relations are interpreted as meaning:

  *If we are in state S and the event E occurs, we should perform the actions A
  and make a transition to the state S'.*

.. warning::

  The Java with lambda support part of Akka is marked as **“experimental”** as of its introduction in
  Akka 2.3.0. We will continue to improve this API based on our users’ feedback, which implies that
  while we try to keep incompatible changes to a minimum, but the binary compatibility guarantee for
  maintenance releases does not apply to the :class:`akka.actor.AbstractFSM`, related classes and the
  :class:`akka.japi.pf` package.

A Simple Example
================

To demonstrate most of the features of the :class:`AbstractFSM` class, consider an
actor which shall receive and queue messages while they arrive in a burst and
send them on after the burst ended or a flush request is received.

First, consider all of the below to use these import statements:

.. includecode:: ../../../akka-samples/akka-docs-java-lambda/src/test/java/docs/actor/fsm/Buncher.java#simple-imports

The contract of our “Buncher” actor is that it accepts or produces the following messages:

.. includecode:: ../../../akka-samples/akka-docs-java-lambda/src/test/java/docs/actor/fsm/Events.java
   :include: simple-events
   :exclude: boilerplate

``SetTarget`` is needed for starting it up, setting the destination for the
``Batches`` to be passed on; ``Queue`` will add to the internal queue while
``Flush`` will mark the end of a burst.

The actor can be in two states: no message queued (aka ``Idle``) or some
message queued (aka ``Active``). The states and the state data is defined like this:

.. includecode:: ../../../akka-samples/akka-docs-java-lambda/src/test/java/docs/actor/fsm/Buncher.java
   :include: simple-state
   :exclude: boilerplate

The actor starts out in the idle state. Once a message arrives it will go to the
active state and stay there as long as messages keep arriving and no flush is
requested. The internal state data of the actor is made up of the target actor
reference to send the batches to and the actual queue of messages.

Now let’s take a look at the skeleton for our FSM actor:

.. includecode:: ../../../akka-samples/akka-docs-java-lambda/src/test/java/docs/actor/fsm/Buncher.java
   :include: simple-fsm
   :exclude: transition-elided,unhandled-elided

The basic strategy is to declare the actor, by inheriting the :class:`AbstractFSM` class
and specifying the possible states and data values as type parameters. Within
the body of the actor a DSL is used for declaring the state machine:

 * :meth:`startWith` defines the initial state and initial data
 * then there is one :meth:`when(<state>) { ... }` declaration per state to be
   handled (could potentially be multiple ones, the passed
   :class:`PartialFunction` will be concatenated using :meth:`orElse`)
 * finally starting it up using :meth:`initialize`, which performs the
   transition into the initial state and sets up timers (if required).

In this case, we start out in the ``Idle`` and ``Uninitialized`` state, where
only the ``SetTarget()`` message is handled; ``stay`` prepares to end this
event’s processing for not leaving the current state, while the ``using``
modifier makes the FSM replace the internal state (which is ``Uninitialized``
at this point) with a fresh ``Todo()`` object containing the target actor
reference. The ``Active`` state has a state timeout declared, which means that
if no message is received for 1 second, a ``FSM.StateTimeout`` message will be
generated. This has the same effect as receiving the ``Flush`` command in this
case, namely to transition back into the ``Idle`` state and resetting the
internal queue to the empty vector. But how do messages get queued? Since this
shall work identically in both states, we make use of the fact that any event
which is not handled by the ``when()`` block is passed to the
``whenUnhandled()`` block:

.. includecode:: ../../../akka-samples/akka-docs-java-lambda/src/test/java/docs/actor/fsm/Buncher.java#unhandled-elided

The first case handled here is adding ``Queue()`` requests to the internal
queue and going to the ``Active`` state (this does the obvious thing of staying
in the ``Active`` state if already there), but only if the FSM data are not
``Uninitialized`` when the ``Queue()`` event is received. Otherwise—and in all
other non-handled cases—the second case just logs a warning and does not change
the internal state.

The only missing piece is where the ``Batches`` are actually sent to the
target, for which we use the ``onTransition`` mechanism: you can declare
multiple such blocks and all of them will be tried for matching behavior in
case a state transition occurs (i.e. only when the state actually changes).

.. includecode:: ../../../akka-samples/akka-docs-java-lambda/src/test/java/docs/actor/fsm/Buncher.java#transition-elided

The transition callback is a partial function which takes as input a pair of
states—the current and the next state. During the state change, the old state
data is available via ``stateData`` as shown, and the new state data would be
available as ``nextStateData``.

To verify that this buncher actually works, it is quite easy to write a test
using the :ref:`akka-testkit`, here using JUnit as an example:

.. includecode:: ../../../akka-samples/akka-docs-java-lambda/src/test/java/docs/actor/fsm/BuncherTest.java
   :include: test-code

Reference
=========

The AbstractFSM Class
---------------------

The :class:`AbstractFSM` abstract class is the base class used to implement an FSM. It implements
Actor since an Actor is created to drive the FSM.

.. includecode:: ../../../akka-samples/akka-docs-java-lambda/src/test/java/docs/actor/fsm/Buncher.java
   :include: simple-fsm
   :exclude: fsm-body

.. note::

   The AbstractFSM class defines a ``receive`` method which handles internal messages
   and passes everything else through to the FSM logic (according to the
   current state). When overriding the ``receive`` method, keep in mind that
   e.g. state timeout handling depends on actually passing the messages through
   the FSM logic.

The :class:`AbstractFSM` class takes two type parameters:

 #. the supertype of all state names, usually an enum,
 #. the type of the state data which are tracked by the :class:`AbstractFSM` module
    itself.

.. _fsm-philosophy:

.. note::

   The state data together with the state name describe the internal state of
   the state machine; if you stick to this scheme and do not add mutable fields
   to the FSM class you have the advantage of making all changes of the
   internal state explicit in a few well-known places.

Defining States
---------------

A state is defined by one or more invocations of the method

  :func:`when(<name>[, stateTimeout = <timeout>])(stateFunction)`.

The given name must be an object which is type-compatible with the first type
parameter given to the :class:`AbstractFSM` class. This object is used as a hash key,
so you must ensure that it properly implements :meth:`equals` and
:meth:`hashCode`; in particular it must not be mutable. The easiest fit for
these requirements are case objects.

If the :meth:`stateTimeout` parameter is given, then all transitions into this
state, including staying, receive this timeout by default. Initiating the
transition with an explicit timeout may be used to override this default, see
`Initiating Transitions`_ for more information. The state timeout of any state
may be changed during action processing with
:func:`setStateTimeout(state, duration)`. This enables runtime configuration
e.g. via external message.

The :meth:`stateFunction` argument is a :class:`PartialFunction[Event, State]`,
which is conveniently given using the state function builder syntax as
demonstrated below:

.. includecode:: ../../../akka-samples/akka-docs-java-lambda/src/test/java/docs/actor/fsm/Buncher.java
   :include: when-syntax

.. warning::

  It is required that you define handlers for each of the possible FSM states,
  otherwise there will be failures when trying to switch to undeclared states.

It is recommended practice to declare the states as an enum and then verify that there is a
``when`` clause for each of the states. If you want to leave the handling of a state
“unhandled” (more below), it still needs to be declared like this:

.. includecode:: ../../../akka-samples/akka-docs-java-lambda/src/test/java/docs/actor/fsm/FSMDocTest.java#NullFunction

Defining the Initial State
--------------------------

Each FSM needs a starting point, which is declared using

  :func:`startWith(state, data[, timeout])`

The optionally given timeout argument overrides any specification given for the
desired initial state. If you want to cancel a default timeout, use
:obj:`Duration.Inf`.

Unhandled Events
----------------

If a state doesn't handle a received event a warning is logged. If you want to
do something else in this case you can specify that with
:func:`whenUnhandled(stateFunction)`:

.. includecode:: ../../../akka-samples/akka-docs-java-lambda/src/test/java/docs/actor/fsm/FSMDocTest.java
   :include: unhandled-syntax

Within this handler the state of the FSM may be queried using the
:meth:`stateName` method.

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

* :meth:`forMax(duration)`

  This modifier sets a state timeout on the next state. This means that a timer
  is started which upon expiry sends a :obj:`StateTimeout` message to the FSM.
  This timer is canceled upon reception of any other message in the meantime;
  you can rely on the fact that the :obj:`StateTimeout` message will not be
  processed after an intervening message.

  This modifier can also be used to override any default timeout which is
  specified for the target state. If you want to cancel the default timeout,
  use :obj:`Duration.Inf`.

* :meth:`using(data)`

  This modifier replaces the old state data with the new data given. If you
  follow the advice :ref:`above <fsm-philosophy>`, this is the only place where
  internal state data are ever modified.

* :meth:`replying(msg)`

  This modifier sends a reply to the currently processed message and otherwise
  does not modify the state transition.

All modifiers can be chained to achieve a nice and concise description:

.. includecode:: ../../../akka-samples/akka-docs-java-lambda/src/test/java/docs/actor/fsm/FSMDocTest.java
   :include: modifier-syntax

The parentheses are not actually needed in all cases, but they visually
distinguish between modifiers and their arguments and therefore make the code
even more pleasant to read for foreigners.

.. note::

   Please note that the ``return`` statement may not be used in :meth:`when`
   blocks or similar; this is a Scala restriction. Either refactor your code
   using ``if () ... else ...`` or move it into a method definition.

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

.. includecode:: ../../../akka-samples/akka-docs-java-lambda/src/test/java/docs/actor/fsm/FSMDocTest.java
   :include: transition-syntax

It is also possible to pass a function object accepting two states to
:func:`onTransition`, in case your transition handling logic is implemented as
a method:

.. includecode:: ../../../akka-samples/akka-docs-java-lambda/src/test/java/docs/actor/fsm/FSMDocTest.java
   :include: alt-transition-syntax

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
actor will be sent a :class:`CurrentState(self, stateName)` message immediately
and will receive :class:`Transition(actorRef, oldState, newState)` messages
whenever a new state is reached. External monitors may be unregistered by
sending :class:`UnsubscribeTransitionCallBack(actorRef)` to the FSM actor.

Stopping a listener without unregistering will not remove the listener from the
subscription list; use :class:`UnsubscribeTransitionCallback` before stopping
the listener.

Timers
------

Besides state timeouts, FSM manages timers identified by :class:`String` names.
You may set a timer using

  :func:`setTimer(name, msg, interval, repeat)`

where :obj:`msg` is the message object which will be sent after the duration
:obj:`interval` has elapsed. If :obj:`repeat` is :obj:`true`, then the timer is
scheduled at fixed rate given by the :obj:`interval` parameter.
Any existing timer with the same name will automatically be canceled before
adding the new timer.

Timers may be canceled using

  :func:`cancelTimer(name)`

which is guaranteed to work immediately, meaning that the scheduled message
will not be processed after this call even if the timer already fired and
queued it. The status of any timer may be inquired with

  :func:`isTimerActive(name)`

These named timers complement state timeouts because they are not affected by
intervening reception of other messages.

Termination from Inside
-----------------------

The FSM is stopped by specifying the result state as

  :func:`stop([reason[, data]])`

The reason must be one of :obj:`Normal` (which is the default), :obj:`Shutdown`
or :obj:`Failure(reason)`, and the second argument may be given to change the
state data which is available during termination handling.

.. note::

   It should be noted that :func:`stop` does not abort the actions and stop the
   FSM immediately. The stop action must be returned from the event handler in
   the same way as a state transition (but note that the ``return`` statement
   may not be used within a :meth:`when` block).

.. includecode:: ../../../akka-samples/akka-docs-java-lambda/src/test/java/docs/actor/fsm/FSMDocTest.java
   :include: stop-syntax

You can use :func:`onTermination(handler)` to specify custom code that is
executed when the FSM is stopped. The handler is a partial function which takes
a :class:`StopEvent(reason, stateName, stateData)` as argument:

.. includecode:: ../../../akka-samples/akka-docs-java-lambda/src/test/java/docs/actor/fsm/FSMDocTest.java
   :include: termination-syntax

As for the :func:`whenUnhandled` case, this handler is not stacked, so each
invocation of :func:`onTermination` replaces the previously installed handler.

Termination from Outside
------------------------

When an :class:`ActorRef` associated to a FSM is stopped using the
:meth:`stop()` method, its :meth:`postStop` hook will be executed. The default
implementation by the :class:`AbstractFSM` class is to execute the
:meth:`onTermination` handler if that is prepared to handle a
:obj:`StopEvent(Shutdown, ...)`.

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

The setting ``akka.actor.debug.fsm`` in :ref:`configuration` enables logging of an
event trace by :class:`LoggingFSM` instances:

.. includecode:: ../../../akka-samples/akka-docs-java-lambda/src/test/java/docs/actor/fsm/FSMDocTest.java
   :include: logging-fsm
   :exclude: body-elided

This FSM will log at DEBUG level:

  * all processed events, including :obj:`StateTimeout` and scheduled timer
    messages
  * every setting and cancellation of named timers
  * all state transitions

Life cycle changes and special messages can be logged as described for
:ref:`Actors <actor.logging-scala>`.

Rolling Event Log
-----------------

The :class:`AbstractLoggingFSM` class adds one more feature to the FSM: a rolling event
log which may be used during debugging (for tracing how the FSM entered a
certain failure state) or for other creative uses:

.. includecode:: ../../../akka-samples/akka-docs-java-lambda/src/test/java/docs/actor/fsm/FSMDocTest.java
   :include: logging-fsm

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

A bigger FSM example contrasted with Actor's :meth:`become`/:meth:`unbecome` can be found in
the `Typesafe Activator <http://www.typesafe.com/platform/getstarted>`_ template named 
`Akka FSM in Scala <http://www.typesafe.com/activator/template/akka-sample-fsm-java-lambda>`_
