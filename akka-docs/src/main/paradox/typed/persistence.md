# Persistence

Akka Persistence is a library for building event sourced actors. For background about how it works
see the @ref:[untyped Akka Persistence section](../persistence.md). This documentation shows how the typed API for persistence
works and assumes you know what is meant by `Command`, `Event` and `State`.

@@@ warning

This module is currently marked as @ref:[may change](../common/may-change.md) in the sense
  of being the subject of active research. This means that API or semantics can
  change without warning or deprecation period and it is not recommended to use
  this module in production just yet—you have been warned.

@@@

## Dependency

To use Akka Persistence Typed, add the module to your project:

@@dependency[sbt,Maven,Gradle] {
  group=com.typesafe.akka
  artifact=akka-persistence-typed_2.12
  version=$akka.version$
}

## Example

Let's start with a simple example. The minimum required for a `PersistentBehavior` is:

Scala
:  @@snip [BasicPersistentBehaviorsSpec.scala]($akka$/akka-persistence-typed/src/test/scala/docs/akka/persistence/typed/BasicPersistentBehaviorsSpec.scala) { #structure }

Java
:  @@snip [BasicPersistentBehaviorsTest.java]($akka$/akka-persistence-typed/src/test/java/jdocs/akka/persistence/typed/BasicPersistentBehaviorsTest.java) { #structure }

The first important thing to notice is the `Behavior` of a persistent actor is typed to the type of the `Command`
because this is the type of message a persistent actor should receive. In Akka Typed this is now enforced by the type system.
The event and state are only used internally.

The components that make up a PersistentBehavior are:

* `persistenceId` is the unique identifier for the persistent actor.
* `initialState` defines the `State` when the entity is first created e.g. a Counter would start with 0 as state.
* `commandHandler` defines how to handle command, resulting in Effects e.g. persisting events, stopping the persistent actor.
* `eventHandler` updates the current state when an event has been persisted.

Next we'll discuss each of these in detail.

### Command handler

The command handler is a function with 3 parameters for the `ActorContext`, current `State`, and `Command`.

A command handler returns an `Effect` directive that defines what event or events, if any, to persist. 
@java[Effects are created using a factory that is returned via the `Effect()` method]
@scala[Effects are created using the `Effect` factory]
and can be used to create various effects such as:

* `persist` will persist one single event or several events atomically, i.e. all events
  are stored or none of them are stored if there is an error
* `none` no events are to be persisted, for example a read-only command
* `unhandled` the command is unhandled (not supported) in current state

External side effects can be performed after successful persist with the `andThen` function e.g 
@scala[`Effect.persist(..).andThen`]@java[`Effect().persist(..).andThen`].

In the example below a reply is sent to the `replyTo` ActorRef. Note that the new state after applying 
the event is passed as parameter to the `andThen` function. All `andThen*` registered callbacks
are executed after successful execution of the persist statement (or immediately, in case of `none` and `unhandled`).

### Event handler

When an event has been persisted successfully the current state is updated by applying the
event to the current state with the `eventHandler`.

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

The command handler just persists the `Cmd` payload in an `Evt`@java[. In this simple example the command handler is defined using a lambda, for the more complicated example below a `CommandHandlerBuilder` is used]:

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
:  @@snip [InDepthPersistentBehaviorSpec.scala]($akka$/akka-persistence-typed/src/test/scala/docs/akka/persistence/typed/InDepthPersistentBehaviorSpec.scala) { #state }

Java
:  @@snip [InDepthPersistentBehaviorTest.java]($akka$/akka-persistence-typed/src/test/java/jdocs/akka/persistence/typed/InDepthPersistentBehaviorTest.java) { #state }

The commands (only a subset are valid depending on state):

Scala
:  @@snip [InDepthPersistentBehaviorSpec.scala]($akka$/akka-persistence-typed/src/test/scala/docs/akka/persistence/typed/InDepthPersistentBehaviorSpec.scala) { #commands }

Java
:  @@snip [InDepthPersistentBehaviorTest.java]($akka$/akka-persistence-typed/src/test/java/jdocs/akka/persistence/typed/InDepthPersistentBehaviorTest.java) { #commands }

The command handler to process each command is decided by a `CommandHandler.byState` command handler,
which is a function from `State => CommandHandler`:

Scala
:  @@snip [InDepthPersistentBehaviorSpec.scala]($akka$/akka-persistence-typed/src/test/scala/docs/akka/persistence/typed/InDepthPersistentBehaviorSpec.scala) { #by-state-command-handler }

Java
:  @@snip [InDepthPersistentBehaviorTest.java]($akka$/akka-persistence-typed/src/test/java/jdocs/akka/persistence/typed/InDepthPersistentBehaviorTest.java) { #by-state-command-handler }

This can refer to many other `CommandHandler`s e.g one for a post that hasn't been started:

Scala
:  @@snip [InDepthPersistentBehaviorSpec.scala]($akka$/akka-persistence-typed/src/test/scala/docs/akka/persistence/typed/InDepthPersistentBehaviorSpec.scala) { #initial-command-handler }

Java
:  @@snip [InDepthPersistentBehaviorTest.java]($akka$/akka-persistence-typed/src/test/java/jdocs/akka/persistence/typed/InDepthPersistentBehaviorTest.java) { #initial-command-handler }

And a different `CommandHandler` for after the post has been added:

Scala
:  @@snip [InDepthPersistentBehaviorSpec.scala]($akka$/akka-persistence-typed/src/test/scala/docs/akka/persistence/typed/InDepthPersistentBehaviorSpec.scala) { #post-added-command-handler }

Java
:  @@snip [InDepthPersistentBehaviorTest.java]($akka$/akka-persistence-typed/src/test/java/jdocs/akka/persistence/typed/InDepthPersistentBehaviorTest.java) { #post-added-command-handler }

The event handler is always the same independent of state. The main reason for not making the event handler
part of the `CommandHandler` is that all events must be handled and that is typically independent of what the
current state is. The event handler can of course still decide what to do based on the state if that is needed.

Scala
:  @@snip [InDepthPersistentBehaviorSpec.scala]($akka$/akka-persistence-typed/src/test/scala/docs/akka/persistence/typed/InDepthPersistentBehaviorSpec.scala) { #event-handler }

Java
:  @@snip [InDepthPersistentBehaviorTest.java]($akka$/akka-persistence-typed/src/test/java/jdocs/akka/persistence/typed/InDepthPersistentBehaviorTest.java) { #event-handler }

And finally the behavior is created from the `byState` command handler:

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

Since it is strongly discouraged to perform side effects in applyEvent,
side effects should be performed once recovery has completed @scala[in the `onRecoveryCompleted` callback.] @java[by overriding `onRecoveryCompleted`]

Scala
:  @@snip [BasicPersistentBehaviorsSpec.scala]($akka$/akka-persistence-typed/src/test/scala/docs/akka/persistence/typed/BasicPersistentBehaviorsSpec.scala) { #recovery }

Java
:  @@snip [BasicPersistentBehaviorsTest.java]($akka$/akka-persistence-typed/src/test/java/jdocs/akka/persistence/typed/BasicPersistentBehaviorsTest.java) { #recovery }

The `onRecoveryCompleted` takes on an `ActorContext` and the current `State`.

## Tagging

Persistence typed allows you to use event tags without using @ref[`EventAdapter`](../persistence.md#event-adapters):

Scala
:  @@snip [BasicPersistentActorSpec.scala]($akka$/akka-persistence-typed/src/test/scala/docs/akka/persistence/typed/BasicPersistentBehaviorsSpec.scala) { #tagging }

Java
:  @@snip [BasicPersistentBehaviorsTest.java]($akka$/akka-persistence-typed/src/test/java/jdocs/akka/persistence/typed/BasicPersistentBehaviorsTest.java) { #tagging }

## Current limitations

* The `PersistentBehavior` can't be wrapped in other behaviors, such as `Behaviors.setup`. See [#23694](https://github.com/akka/akka/issues/23694)


