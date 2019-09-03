# Behaviors as Finite state machines

With classic actors there is explicit support for building @ref[Finite State Machines](../fsm.md). No support
is needed in Akka Typed as it is straightforward to represent FSMs with behaviors. 

To see how the Akka Typed API can be used to model FSMs here's the Buncher example ported from
the @ref[classic actor FSM docs](../fsm.md). It demonstrates how to:

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

Classic `FSM`s also have a `D` (data) type parameter. Akka Typed doesn't need to be aware of this and it can be stored
via defining your behaviors as methods.

Scala
:  @@snip [FSMSocSpec.scala](/akka-actor-typed-tests/src/test/scala/docs/akka/typed/FSMDocSpec.scala) { #storing-state }

Java
:  @@snip [FSMSocTest.java](/akka-actor-typed-tests/src/test/java/jdocs/akka/typed/FSMDocTest.java) { #storing-state }

Each state becomes a distinct behavior. No explicit `goto` is required as Akka Typed
already requires you return the next behavior.

Scala
:  @@snip [FSMSocSpec.scala](/akka-actor-typed-tests/src/test/scala/docs/akka/typed/FSMDocSpec.scala) { #simple-state }

Java
:  @@snip [FSMSocTest.java](/akka-actor-typed-tests/src/test/java/jdocs/akka/typed/FSMDocTest.java) { #simple-state}

To set state timeouts use `Behaviors.withTimers` along with a `startSingleTimer`.

Any side effects that were previously done in a `onTransition` block go directly into the behaviors.








