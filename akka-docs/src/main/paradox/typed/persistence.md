# Persistence

## Dependency

To use Akka Persistence Typed, add the module to your project:

@@dependency[sbt,Maven,Gradle] {
  group=com.typesafe.akka
  artifact=akka-persistence-typed_$scala.binary_version$
  version=$akka.version$
}

## Introduction

Akka Persistence is a library for building event sourced actors. For background about how it works
see the @ref:[untyped Akka Persistence section](../persistence.md). This documentation shows how the typed API for persistence
works and assumes you know what is meant by `Command`, `Event` and `State`.

@@@ warning

This module is currently marked as @ref:[may change](../common/may-change.md) in the sense
  of being the subject of active research. This means that API or semantics can
  change without warning or deprecation period and it is not recommended to use
  this module in production just yet—you have been warned.

@@@

## Example

Let's start with a simple example. The minimum required for a `PersistentBehavior` is:

Scala
:  @@snip [BasicPersistentBehaviorsCompileOnly.scala]($akka$/akka-persistence-typed/src/test/scala/docs/akka/persistence/typed/BasicPersistentBehaviorsCompileOnly.scala) { #structure }

Java
:  @@snip [BasicPersistentBehaviorsTest.java]($akka$/akka-persistence-typed/src/test/java/jdocs/akka/persistence/typed/BasicPersistentBehaviorsTest.java) { #structure }

The first important thing to notice is the `Behavior` of a persistent actor is typed to the type of the `Command`
because this is the type of message a persistent actor should receive. In Akka Typed this is now enforced by the type system.
The event and state are only used internally.

The components that make up a PersistentBehavior are:

* `persistenceId` is the stable unique identifier for the persistent actor.
* `emptyState` defines the `State` when the entity is first created e.g. a Counter would start with 0 as state.
* `commandHandler` defines how to handle command by producing Effects e.g. persisting events, stopping the persistent actor.
* `eventHandler` returns the new state given the current state when an event has been persisted.

Next we'll discuss each of these in detail.

### Command handler

The command handler is a function with @java[2 parameters for]@scala[3 parameters for the `ActorContext`,]
current `State`, and `Command`.

A command handler returns an `Effect` directive that defines what event or events, if any, to persist. 
@java[Effects are created using a factory that is returned via the `Effect()` method]
@scala[Effects are created using the `Effect` factory]
and can be used to create various effects such as:

* `persist` will persist one single event or several events atomically, i.e. all events
  are stored or none of them are stored if there is an error
* `none` no events are to be persisted, for example a read-only command
* `unhandled` the command is unhandled (not supported) in current state

External side effects are to be performed after successful persist which is achieved with the `andThen` function e.g @scala[`Effect.persist(..).andThen`]@java[`Effect().persist(..).andThen`].

In the example below a reply is sent to the `replyTo` ActorRef. Note that the new state after applying 
the event is passed as parameter to the `andThen` function. All `andThen*` registered callbacks
are executed after successful execution of the persist statement (or immediately, in case of `none` and `unhandled`).

### Event handler

When an event has been persisted successfully the new state is created by applying the event to the current state with the `eventHandler`.

The event handler returns the new state, which must be immutable so you return a new instance of the state.
The same event handler is also used when the entity is started up to recover its state from the stored events.

It is not recommended to perform side effects
in the event handler, as those are also executed during recovery of an persistent actor

## Basic example

Command and event:

Scala
:  @@snip [PersistentActorCompileOnyTest.scala]($akka$/akka-persistence-typed/src/test/scala/akka/persistence/typed/scaladsl/PersistentActorCompileOnlyTest.scala) { #command }

Java
:  @@snip [PersistentActorCompileOnyTest.java]($akka$/akka-persistence-typed/src/test/java/akka/persistence/typed/javadsl/PersistentActorCompileOnlyTest.java) { #command }

State is a List containing all the events:

Scala
:  @@snip [PersistentActorCompileOnyTest.scala]($akka$/akka-persistence-typed/src/test/scala/akka/persistence/typed/scaladsl/PersistentActorCompileOnlyTest.scala) { #state }

Java
:  @@snip [PersistentActorCompileOnyTest.java]($akka$/akka-persistence-typed/src/test/java/akka/persistence/typed/javadsl/PersistentActorCompileOnlyTest.java) { #state }

The command handler persists the `Cmd` payload in an `Evt`@java[. In this simple example the command handler is defined using a lambda, for the more complicated example below a `CommandHandlerBuilder` is used]:

Scala
:  @@snip [PersistentActorCompileOnyTest.scala]($akka$/akka-persistence-typed/src/test/scala/akka/persistence/typed/scaladsl/PersistentActorCompileOnlyTest.scala) { #command-handler }

Java
:  @@snip [PersistentActorCompileOnyTest.java]($akka$/akka-persistence-typed/src/test/java/akka/persistence/typed/javadsl/PersistentActorCompileOnlyTest.java) { #command-handler }

The event handler appends the event to the state. This is called after successfully
persisting the event in the database @java[. As with the command handler the event handler is defined using a lambda, see below for a more complicated example using the `EventHandlerBuilder`]:

Scala
:  @@snip [PersistentActorCompileOnyTest.scala]($akka$/akka-persistence-typed/src/test/scala/akka/persistence/typed/scaladsl/PersistentActorCompileOnlyTest.scala) { #event-handler }

Java
:  @@snip [PersistentActorCompileOnyTest.java]($akka$/akka-persistence-typed/src/test/java/akka/persistence/typed/javadsl/PersistentActorCompileOnlyTest.java) { #event-handler }

These are used to create a `PersistentBehavior`:

Scala
:  @@snip [PersistentActorCompileOnyTest.scala]($akka$/akka-persistence-typed/src/test/scala/akka/persistence/typed/scaladsl/PersistentActorCompileOnlyTest.scala) { #behavior }

Java
:  @@snip [PersistentActorCompileOnyTest.java]($akka$/akka-persistence-typed/src/test/java/akka/persistence/typed/javadsl/PersistentActorCompileOnlyTest.java) { #behavior }

The `PersistentBehavior` can then be run as with any plain typed actor as described in [typed actors documentation](actors-typed.md).

@java[The `ActorContext` can be obtained with `Behaviors.setup` and be passed as a constructor parameter]

## Larger example

After processing a message, plain typed actors are able to return the `Behavior` that is used
for next message.

As you can see in the above examples this is not supported by typed persistent actors. Instead, the state is
returned by `eventHandler`. The reason a new behavior can't be returned is that behavior is part of the actor's
state and must also carefully be reconstructed during recovery. If it would have been supported it would mean
that the behavior must be restored when replaying events and also encoded in the state anyway when snapshots are used.
That would be very prone to mistakes and thus not allowed in Typed Persistence.

For basic actors you can use the same set of command handlers independent of what state the entity is in,
as shown in above example. For more complex actors it's useful to be able to change the behavior in the sense
that different functions for processing commands may be defined depending on what state the actor is in. This is useful when implementing finite state machine (FSM) like entities.

The next example shows how to define different behavior based on the current `State`. It is an actor that
represents the state of a blog post. Before a post is started the only command it can process is to `AddPost`. Once it is started
then it we can look it up with `GetPost`, modify it with `ChangeBody` or publish it with `Publish`.

The state is captured by:

Scala
:  @@snip [InDepthPersistentBehaviorSpec.scala]($akka$/akka-persistence-typed/src/test/scala/docs/akka/persistence/typed/InDepthPersistentBehaviorSpec.scala) { #state }

Java
:  @@snip [InDepthPersistentBehaviorTest.java]($akka$/akka-persistence-typed/src/test/java/jdocs/akka/persistence/typed/InDepthPersistentBehaviorTest.java) { #state }

The commands, of which only a subset are valid depending on the state:

Scala
:  @@snip [InDepthPersistentBehaviorSpec.scala]($akka$/akka-persistence-typed/src/test/scala/docs/akka/persistence/typed/InDepthPersistentBehaviorSpec.scala) { #commands }

Java
:  @@snip [InDepthPersistentBehaviorTest.java]($akka$/akka-persistence-typed/src/test/java/jdocs/akka/persistence/typed/InDepthPersistentBehaviorTest.java) { #commands }

@java[The commandler handler to process each command is decided by the state class (or state predicate) that is
given to the `commandHandlerBuilder` and the match cases in the builders. Several builders can be composed with `orElse`:]
@scala[The command handler to process each command is decided by a `CommandHandler.byState` command handler,
which is a function from `State => CommandHandler`:]

Scala
:  @@snip [InDepthPersistentBehaviorSpec.scala]($akka$/akka-persistence-typed/src/test/scala/docs/akka/persistence/typed/InDepthPersistentBehaviorSpec.scala) { #by-state-command-handler }

Java
:  @@snip [InDepthPersistentBehaviorTest.java]($akka$/akka-persistence-typed/src/test/java/jdocs/akka/persistence/typed/InDepthPersistentBehaviorTest.java) { #command-handler }

The @java[`CommandHandlerBuilder`]@scala[`CommandHandler`] for a post that hasn't been initialized with content:

Scala
:  @@snip [InDepthPersistentBehaviorSpec.scala]($akka$/akka-persistence-typed/src/test/scala/docs/akka/persistence/typed/InDepthPersistentBehaviorSpec.scala) { #initial-command-handler }

Java
:  @@snip [InDepthPersistentBehaviorTest.java]($akka$/akka-persistence-typed/src/test/java/jdocs/akka/persistence/typed/InDepthPersistentBehaviorTest.java) { #initial-command-handler }

And a different @java[`CommandHandlerBuilder`]@scala[`CommandHandler`] for after the post content has been added:

Scala
:  @@snip [InDepthPersistentBehaviorSpec.scala]($akka$/akka-persistence-typed/src/test/scala/docs/akka/persistence/typed/InDepthPersistentBehaviorSpec.scala) { #post-added-command-handler }

Java
:  @@snip [InDepthPersistentBehaviorTest.java]($akka$/akka-persistence-typed/src/test/java/jdocs/akka/persistence/typed/InDepthPersistentBehaviorTest.java) { #post-added-command-handler }

The event handler is always the same independent of state. The main reason for not making the event handler
part of the `CommandHandler` is that contrary to Commands, all events must be handled and that is typically independent of what the
current state is. The event handler can still decide what to do based on the state, if that is needed.

Scala
:  @@snip [InDepthPersistentBehaviorSpec.scala]($akka$/akka-persistence-typed/src/test/scala/docs/akka/persistence/typed/InDepthPersistentBehaviorSpec.scala) { #event-handler }

Java
:  @@snip [InDepthPersistentBehaviorTest.java]($akka$/akka-persistence-typed/src/test/java/jdocs/akka/persistence/typed/InDepthPersistentBehaviorTest.java) { #event-handler }

And finally the behavior is created @scala[from the `PersistentBehaviors.receive`]:

Scala
:  @@snip [InDepthPersistentBehaviorSpec.scala]($akka$/akka-persistence-typed/src/test/scala/docs/akka/persistence/typed/InDepthPersistentBehaviorSpec.scala) { #behavior }

Java
:  @@snip [InDepthPersistentBehaviorTest.java]($akka$/akka-persistence-typed/src/test/java/jdocs/akka/persistence/typed/InDepthPersistentBehaviorTest.java) { #behavior }

## Serialization

The same @ref:[serialization](../serialization.md) mechanism as for untyped
actors is also used in Akka Typed, also for persistent actors. When picking serialization solution for the events
you should also consider that it must be possible read old events when the application has evolved.
Strategies for that can be found in the @ref:[schema evolution](../persistence-schema-evolution.md).

## Recovery

It is strongly discouraged to perform side effects in `applyEvent`,
so side effects should be performed once recovery has completed @scala[in the `onRecoveryCompleted` callback.] @java[by overriding `onRecoveryCompleted`]

Scala
:  @@snip [BasicPersistentBehaviorsCompileOnly.scala]($akka$/akka-persistence-typed/src/test/scala/docs/akka/persistence/typed/BasicPersistentBehaviorsCompileOnly.scala) { #recovery }

Java
:  @@snip [BasicPersistentBehaviorsTest.java]($akka$/akka-persistence-typed/src/test/java/jdocs/akka/persistence/typed/BasicPersistentBehaviorsTest.java) { #recovery }

The `onRecoveryCompleted` takes @scala[an `ActorContext` and] the current `State`,
and doesn't return anything.

## Tagging

Persistence typed allows you to use event tags without using @ref[`EventAdapter`](../persistence.md#event-adapters):

Scala
:  @@snip [BasicPersistentActorCompileOnly.scala]($akka$/akka-persistence-typed/src/test/scala/docs/akka/persistence/typed/BasicPersistentBehaviorsCompileOnly.scala) { #tagging }

Java
:  @@snip [BasicPersistentBehaviorsTest.java]($akka$/akka-persistence-typed/src/test/java/jdocs/akka/persistence/typed/BasicPersistentBehaviorsTest.java) { #tagging }

## Event adapters

Event adapters can be programmatically added to your `PersistentBehavior`s that can convert from your `Event` type
to another type that is then passed to the journal.

Defining an event adapter is done by extending an EventAdapter:

Scala
:  @@snip [x]($akka$/akka-persistence-typed/src/test/scala/akka/persistence/typed/scaladsl/PersistentBehaviorSpec.scala) { #event-wrapper }

Java
:  @@snip [x]($akka$/akka-persistence-typed/src/test/java/akka/persistence/typed/javadsl/PersistentActorCompileOnlyTest.java) { #event-wrapper }

Then install it on a persistent behavior:

Scala
:  @@snip [x]($akka$/akka-persistence-typed/src/test/scala/akka/persistence/typed/scaladsl/PersistentBehaviorSpec.scala) { #install-event-adapter }

Java
:  @@snip [x]($akka$/akka-persistence-typed/src/test/java/akka/persistence/typed/javadsl/PersistentActorCompileOnlyTest.java) { #install-event-adapter }

## Wrapping Persistent Behaviors

When creating a `PersistentBehavior`, it is possible to wrap `PersistentBehavior` in
other behaviors such as `Behaviors.setup` in order to access the `ActorContext` object. For instance
to access the actor logging upon taking snapshots for debug purpose.

Scala
:  @@snip [BasicPersistentActorCompileOnly.scala]($akka$/akka-persistence-typed/src/test/scala/docs/akka/persistence/typed/BasicPersistentBehaviorsCompileOnly.scala) { #wrapPersistentBehavior }

Java
:  @@snip [BasicPersistentBehaviorsTest.java]($akka$/akka-persistence-typed/src/test/java/jdocs/akka/persistence/typed/BasicPersistentBehaviorsTest.java) { #wrapPersistentBehavior }
