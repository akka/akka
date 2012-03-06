.. _fsm-java:

###########################################
Building Finite State Machine Actors (Java)
###########################################


Overview
========

The FSM (Finite State Machine) pattern is best described in the `Erlang design
principles
<http://www.erlang.org/documentation/doc-4.8.2/doc/design_principles/fsm.html>`_.
In short, it can be seen as a set of relations of the form:

  **State(S) x Event(E) -> Actions (A), State(S')**

These relations are interpreted as meaning:

  *If we are in state S and the event E occurs, we should perform the actions A
  and make a transition to the state S'.*

While the Scala programming language enables the formulation of a nice internal
DSL (domain specific language) for formulating finite state machines (see
:ref:`fsm-scala`), Java’s verbosity does not lend itself well to the same
approach. This chapter describes ways to effectively achieve the same
separation of concerns through self-discipline.

How State should be Handled
===========================

All mutable fields (or transitively mutable data structures) referenced by the
FSM actor’s implementation should be collected in one place and only mutated
using a small well-defined set of methods. One way to achieve this is to
assemble all mutable state in a superclass which keeps it private and offers
protected methods for mutating it.

.. includecode:: code/akka/docs/actor/FSMDocTestBase.java#imports-data

.. includecode:: code/akka/docs/actor/FSMDocTestBase.java#base

The benefit of this approach is that state changes can be acted upon in one
central place, which makes it impossible to forget inserting code for reacting
to state transitions when adding to the FSM’s machinery.

Message Buncher Example
=======================

The base class shown above is designed to support a similar example as for the
Scala FSM documentation: an actor which receives and queues messages, to be
delivered in batches to a configurable target actor. The messages involved are:

.. includecode:: code/akka/docs/actor/FSMDocTestBase.java#data

This actor has only the two states ``IDLE`` and ``ACTIVE``, making their
handling quite straight-forward in the concrete actor derived from the base
class:

.. includecode:: code/akka/docs/actor/FSMDocTestBase.java#imports-actor

.. includecode:: code/akka/docs/actor/FSMDocTestBase.java#actor

The trick here is to factor out common functionality like :meth:`whenUnhandled`
and :meth:`transition` in order to obtain a few well-defined points for
reacting to change or insert logging.

State-Centric vs. Event-Centric
===============================

In the example above, the subjective complexity of state and events was roughly
equal, making it a matter of taste whether to choose primary dispatch on
either; in the example a state-based dispatch was chosen. Depending on how
evenly the matrix of possible states and events is populated, it may be more
practical to handle different events first and distinguish the states in the
second tier. An example would be a state machine which has a multitude of
internal states but handles only very few distinct events.
