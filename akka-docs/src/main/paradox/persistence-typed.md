# Persistence 

@@@ warning

This module is currently marked as @ref:[may change](common/may-change.md) in the sense
  of being the subject of active research. This means that API or semantics can
  change without warning or deprecation period and it is not recommended to use
  this module in production just yet—you have been warned.
  
@@@

@@@ warning

This module only has a Scala DSL. See [#24193](https://github.com/akka/akka/issues/24193) 
to track progress and to contribute to the Java DSL.
  
@@@

To use typed persistence add the following dependency:

@@dependency [sbt,Maven,Gradle] {
  group=com.typesafe.akka
  artifact=akka-persistence-typed_2.11
  version=$akka.version$
}



Akka Persistence is a library for building event sourced actors. For background about how it works
see the @ref:[untyped Akka Persistence section](persistence.md). This documentation shows how the typed API for persistence
works and assumes you know what is meant by `Command`, `Event` and `State`.

Let's start with a simple example. The minimum required for a `PersistentActor` is:

Scala
:  @@snip [BasicPersistentActorSpec.scala]($akka$/akka-persistence-typed/src/test/scala/docs/akka/persistence/typed/BasicPersistentActorSpec.scala) { #structure }

The first important thing to notice is the `Behavior` of a `PersistentActor` is typed to the type of the `Command` 
because this type of message a persistent actor should receive. In Akka Typed this is now enforced by the type system.
The event and state are only used internally.

The parameters to `PersistentActor.immutable` are::

* `persistenceId` is the unique identifier for the persistent actor.
* `initialState` defines the `State` when the entity is first created e.g. a Counter would start wiht 0 as state.
* `commandHandler` defines how to handle command and optional functions for other signals, e.g. `Termination` messages if `watch` is used.
* `eventHandler` updates the current state when an event has been persisted.

Next we'll discuss each of these in detail.

### Command handler

The command handler is a function with 3 parameters for the `ActorContext`, current `State`, and `Command`.

A command handler returns an `Effect` directive that defines what event or events, if any, to persist.

* `Effect.persist` will persist one single event or several events atomically, i.e. all events
  are stored or none of them are stored if there is an error
* `Effect.none` no events are to be persisted, for example a read-only command
* `Effect.unhandled` the command is unhandled (not supported) in current state

External side effects can be performed after successful persist with the `andThen` function e.g `Effect.persist(..).andThen`. 
In the example below a reply is sent to the `replyTo` ActorRef. Note that the new state after applying 
the event is passed as parameter to the `andThen` function.

### Event handler

When an event has been persisted successfully the current state is updated by applying the 
event to the current state with the `eventHandler` function. 

The event handler returns the new state, which must be immutable so you return a new instance of the state. 
The same event handler is also used when the entity is started up to recover its state from the stored events.

It is not recommended to perform side effects 
in the event handler, as those are also executed during recovery of an persistent actor

## Basic example

Command and event:

Scala
:  @@snip [PersistentActorCompileOnyTest.scala]($akka$/akka-persistence-typed/src/test/scala/akka/persistence/typed/scaladsl/PersistentActorCompileOnlyTest.scala) { #command }

State is a List containing all the events:

Scala
:  @@snip [PersistentActorCompileOnyTest.scala]($akka$/akka-persistence-typed/src/test/scala/akka/persistence/typed/scaladsl/PersistentActorCompileOnlyTest.scala) { #state }

The command handler just persists the `Cmd` payload in an `Evt`:

Scala
:  @@snip [PersistentActorCompileOnyTest.scala]($akka$/akka-persistence-typed/src/test/scala/akka/persistence/typed/scaladsl/PersistentActorCompileOnlyTest.scala) { #command-handler }

The event handler appends the event to the state. This is called after successfully
persisting the event in the database:

Scala
:  @@snip [PersistentActorCompileOnyTest.scala]($akka$/akka-persistence-typed/src/test/scala/akka/persistence/typed/scaladsl/PersistentActorCompileOnlyTest.scala) { #event-handler }

These are used to create a `PersistentBehavior`:

Scala
:  @@snip [PersistentActorCompileOnyTest.scala]($akka$/akka-persistence-typed/src/test/scala/akka/persistence/typed/scaladsl/PersistentActorCompileOnlyTest.scala) { #behavior }

The behavior can then be run as with any normal typed actor as described in [typed actors documentation](actors-typed.md).

## Larger example

After processing a message plain typed actors are able to return the `Behavior` that is used 
for next message. 

As you can see in the above examples this is not supported by typed persistent actors. Instead, the state is 
returned by `eventHandler`. The reason a new behavior can't be returned is that behavior is part of the actor's 
state and must also carefully be reconstructed during recovery. If it would have been supported it would mean 
that the behavior must be restored when replaying events and also encoded in the state anyway when snapshots are used. 
That would be very prone to mistakes and thus not allowed in Typed Persistence.

For simple actors you can use the same set of command handlers independent of what state the entity is in, 
as shown in above example. For more complex actors it's useful to be able to change the behavior in the sense 
that different functions for processing commands may be defined depending on what state the actor is in. This is useful when implementing finite state machine (FSM) like entities. 

The next example shows how to define different behavior based on the current `State`. It is an actor that
represents the state of a blog post. Before a post is started the only command it can process is to `AddPost`. Once it is started
then it we can look it up with `GetPost`, modify it with `ChangeBody` or publish it with `Publish`.

The state is captured by:

Scala
:  @@snip [InDepthPersistentActorSpec.scala]($akka$/akka-persistence-typed/src/test/scala/docs/akka/persistence/typed/InDepthPersistentActorSpec.scala) { #state }

The commands (only a subset are valid depending on state):

Scala
:  @@snip [InDepthPersistentActorSpec.scala]($akka$/akka-persistence-typed/src/test/scala/docs/akka/persistence/typed/InDepthPersistentActorSpec.scala) { #commands }

The command handler to process each command is decided by a `CommandHandler.byState` command handler, 
which is a function from `State => CommandHandler`:

Scala
:  @@snip [InDepthPersistentActorSpec.scala]($akka$/akka-persistence-typed/src/test/scala/docs/akka/persistence/typed/InDepthPersistentActorSpec.scala) { #by-state-command-handler }

This can refer to many other `CommandHandler`s e.g one for a post that hasn't been started:

Scala
:  @@snip [InDepthPersistentActorSpec.scala]($akka$/akka-persistence-typed/src/test/scala/docs/akka/persistence/typed/InDepthPersistentActorSpec.scala) { #initial-command-handler }

And a different `CommandHandler` for after the post has been added:

Scala
:  @@snip [InDepthPersistentActorSpec.scala]($akka$/akka-persistence-typed/src/test/scala/docs/akka/persistence/typed/InDepthPersistentActorSpec.scala) { #post-added-command-handler }

The event handler is always the same independent of state. The main reason for not making the event handler 
part of the `CommandHandler` is that all events must be handled and that is typically independent of what the 
current state is. The event handler can of course still decide what to do based on the state if that is needed.

Scala
:  @@snip [InDepthPersistentActorSpec.scala]($akka$/akka-persistence-typed/src/test/scala/docs/akka/persistence/typed/InDepthPersistentActorSpec.scala) { #event-handler }

And finally the behavior is created from the `byState` command handler:

Scala
:  @@snip [InDepthPersistentActorSpec.scala]($akka$/akka-persistence-typed/src/test/scala/docs/akka/persistence/typed/InDepthPersistentActorSpec.scala) { #behavior }

## Serialization

The same @ref:[serialization](serialization.md) mechanism as for untyped 
actors is also used in Akka Typed, also for persistent actors. When picking serialization solution for the events 
you should also consider that it must be possible read old events when the application has evolved. 
Strategies for that can be found in the @ref:[schema evolution](persistence-schema-evolution.md).

## Recovery

Since it is strongly discouraged to perform side effects in applyEvent , 
side effects should be performed once recovery has completed in the `onRecoveryCompleted` callback

Scala
:  @@snip [BasicPersistentActorSpec.scala]($akka$/akka-persistence-typed/src/test/scala/docs/akka/persistence/typed/BasicPersistentActorSpec.scala) { #recovery }

The `onRecoveryCompleted` takes on an `ActorContext` and the current `State`.

## Tagging

Persistence typed allows you to use event tags with the following `withTagging` method,
without using @ref[`EventAdapter`](persistence.md#event-adapters).

Scala
:  @@snip [BasicPersistentActorSpec.scala]($akka$/akka-persistence-typed/src/test/scala/docs/akka/persistence/typed/BasicPersistentActorSpec.scala) { #tagging }

## Current limitations

* The `PersistentBehavior` can't be wrapped in other behaviors, such as `Actor.deferred`. See [#23694](https://github.com/akka/akka/issues/23694)
* Can only tag events with event adapters. See [#23817](https://github.com/akka/akka/issues/23817)
* Missing Java DSL. See [#24193](https://github.com/akka/akka/issues/24193)
* Snapshot support. See [#24196](https://github.com/akka/akka/issues/24196)


