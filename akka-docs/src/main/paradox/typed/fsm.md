---
project.description: Finite State Machines (FSM) with Akka Actors.
---
# Behaviors as finite state machines

For the Akka Classic documentation of this feature see @ref:[Classic FSM](../fsm.md).

An actor can be used to model a Finite State Machine (FSM).

To demonstrate this, consider an actor which shall receive and queue messages while they arrive in a burst and
send them on after the burst ended or a flush request is received.

This example demonstrates how to:

* Model states using different behaviors
* Model storing data at each state by representing the behavior as a method 
* Implement state timeouts 

The events the FSM can receive become the type of message the Actor can receive:

Scala
:  @@snip [FSMSocSpec.scala](/akka-actor-typed-tests/src/test/scala/docs/akka/typed/FSMDocSpec.scala) { #simple-events }

Java
:  @@snip [FSMSocTest.java](/akka-actor-typed-tests/src/test/java/jdocs/akka/typed/FSMDocTest.java) { #simple-events }

`SetTarget` is needed for starting it up, setting the destination for the
`Batches` to be passed on; `Queue` will add to the internal queue while
`Flush` will mark the end of a burst.

Scala
:  @@snip [FSMSocSpec.scala](/akka-actor-typed-tests/src/test/scala/docs/akka/typed/FSMDocSpec.scala) { #storing-state }

Java
:  @@snip [FSMSocTest.java](/akka-actor-typed-tests/src/test/java/jdocs/akka/typed/FSMDocTest.java) { #storing-state }

Each state becomes a distinct behavior and after processing a message the next state in the form of a `Behavior`
is returned.

Scala
:  @@snip [FSMSocSpec.scala](/akka-actor-typed-tests/src/test/scala/docs/akka/typed/FSMDocSpec.scala) { #simple-state }

Java
:  @@snip [FSMSocTest.java](/akka-actor-typed-tests/src/test/java/jdocs/akka/typed/FSMDocTest.java) { #simple-state}

@scala[
The method `idle` above makes use of `Behaviors.unhandled` which advises the system to reuse the previous behavior, 
including the hint that the message has not been handled.
There are two related behaviors:

- return `Behaviors.empty` as next behavior in case you reached a state where you don't expect messages any more. 
  For instance if an actor only waits until all spawned child actors stopped. 
  Unhandled messages are still logged with this behavior.
- return `Behaviors.ignore` as next behavior in case you don't care about unhandled messages. 
  All messages sent to an actor with such a behavior are simply dropped and ignored (without logging)
]

To set state timeouts use `Behaviors.withTimers` along with a `startSingleTimer`.

## Example project

@java[@extref[FSM example project](samples:akka-samples-fsm-java)]
@scala[@extref[FSM example project](samples:akka-samples-fsm-scala)]
is an example project that can be downloaded, and with instructions of how to run.

This project contains a Dining Hakkers sample illustrating how to model a Finite State Machine (FSM) with actors.
