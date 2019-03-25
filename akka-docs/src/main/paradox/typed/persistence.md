# Persistence

@@@ index

* [Persistence - coding style](persistence-style.md)
* [Persistence - snapshotting](persistence-snapshot.md)

@@@

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
  of being the subject of final development. This means that API or semantics can
  change without warning or deprecation period and it is not recommended to use
  this module in production just yet.

@@@

## Example

Let's start with a simple example. The minimum required for a `EventSourcedBehavior` is:

Scala
:  @@snip [BasicPersistentBehaviorCompileOnly.scala](/akka-persistence-typed/src/test/scala/docs/akka/persistence/typed/BasicPersistentBehaviorCompileOnly.scala) { #structure }

Java
:  @@snip [BasicPersistentBehaviorTest.java](/akka-persistence-typed/src/test/java/jdocs/akka/persistence/typed/BasicPersistentBehaviorTest.java) { #structure }

The first important thing to notice is the `Behavior` of a persistent actor is typed to the type of the `Command`
because this is the type of message a persistent actor should receive. In Akka Typed this is now enforced by the type system.
The event and state are only used internally.

The components that make up a EventSourcedBehavior are:

* `persistenceId` is the stable unique identifier for the persistent actor.
* `emptyState` defines the `State` when the entity is first created e.g. a Counter would start with 0 as state.
* `commandHandler` defines how to handle command by producing Effects e.g. persisting events, stopping the persistent actor.
* `eventHandler` returns the new state given the current state when an event has been persisted.

Next we'll discuss each of these in detail.

### Command handler

The command handler is a function with 2 parameters, the current `State` and the incoming `Command`.

A command handler returns an `Effect` directive that defines what event or events, if any, to persist. 
Effects are created using @java[a factory that is returned via the `Effect()` method] @scala[the `Effect` factory]
and can be one of: 

* `persist` will persist one single event or several events atomically, i.e. all events
  are stored or none of them are stored if there is an error
* `none` no events are to be persisted, for example a read-only command
* `unhandled` the command is unhandled (not supported) in current state
* `stop` stop this actor

In addition to returning the primary `Effect` for the command `EventSourcedBehavior`s can also 
chain side effects (`SideEffect`s) are to be performed after successful persist which is achieved with the `andThen`  and `thenRun`
function e.g @scala[`Effect.persist(..).andThen`]@java[`Effect().persist(..).andThen`]. The `thenRun` function
is a convenience around creating a `SideEffect`.

In the example below a reply is sent to the `replyTo` ActorRef. Note that the new state after applying 
the event is passed as parameter to the `thenRun` function. All `thenRun` registered callbacks
are executed sequentially after successful execution of the persist statement (or immediately, in case of `none` and `unhandled`).

### Event handler

When an event has been persisted successfully the new state is created by applying the event to the current state with the `eventHandler`.

The state is typically defined as an immutable class and then the event handler returns a new instance of the state.
You may choose to use a mutable class for the state, and then the event handler may update the state instance and
return the same instance. Both immutable and mutable state is supported.

The same event handler is also used when the entity is started up to recover its state from the stored events.

The event handler should only update the state and never perform side effects, as those would also be
executed during recovery of the persistent actor.

### Completing the example

Let's fill in the details of the example.

Command and event:

Scala
:  @@snip [BasicPersistentBehaviorCompileOnly.scala](/akka-persistence-typed/src/test/scala/docs/akka/persistence/typed/BasicPersistentBehaviorCompileOnly.scala) { #command }

Java
:  @@snip [BasicPersistentBehaviorTest.java](/akka-persistence-typed/src/test/java/jdocs/akka/persistence/typed/BasicPersistentBehaviorTest.java) { #command }

State is a List containing the 5 latest items:

Scala
:  @@snip [BasicPersistentBehaviorCompileOnly.scala](/akka-persistence-typed/src/test/scala/docs/akka/persistence/typed/BasicPersistentBehaviorCompileOnly.scala) { #state }

Java
:  @@snip [BasicPersistentBehaviorTest.java](/akka-persistence-typed/src/test/java/jdocs/akka/persistence/typed/BasicPersistentBehaviorTest.java) { #state }

The command handler persists the `Add` payload in an `Added` event:

Scala
:  @@snip [BasicPersistentBehaviorCompileOnly.scala](/akka-persistence-typed/src/test/scala/docs/akka/persistence/typed/BasicPersistentBehaviorCompileOnly.scala) { #command-handler }

Java
:  @@snip [BasicPersistentBehaviorTest.java](/akka-persistence-typed/src/test/java/jdocs/akka/persistence/typed/BasicPersistentBehaviorTest.java) { #command-handler }

The event handler appends the item to the state and keeps 5 items. This is called after successfully
persisting the event in the database:

Scala
:  @@snip [BasicPersistentBehaviorCompileOnly.scala](/akka-persistence-typed/src/test/scala/docs/akka/persistence/typed/BasicPersistentBehaviorCompileOnly.scala) { #event-handler }

Java
:  @@snip [BasicPersistentBehaviorTest.java](/akka-persistence-typed/src/test/java/jdocs/akka/persistence/typed/BasicPersistentBehaviorTest.java) { #event-handler }

@scala[These are used to create a `EventSourcedBehavior`:]
@java[These are defined in an `EventSourcedBehavior`:]

Scala
:  @@snip [BasicPersistentBehaviorCompileOnly.scala](/akka-persistence-typed/src/test/scala/docs/akka/persistence/typed/BasicPersistentBehaviorCompileOnly.scala) { #behavior }

Java
:  @@snip [BasicPersistentBehaviorTest.java](/akka-persistence-typed/src/test/java/jdocs/akka/persistence/typed/BasicPersistentBehaviorTest.java) { #behavior }

## Cluster Sharding and persistence

In a use case where the number of persistent actors needed are higher than what would fit in the memory of one node or
where resilience is important so that if a node crashes the persistent actors are quickly started on a new node and can
resume operations @ref:[Cluster Sharding](cluster-sharding.md) is an excellent fit to spread persistent actors over a
cluster and address them by id.

The `EventSourcedBehavior` can then be run as with any plain typed actor as described in @ref:[actors documentation](actors.md),
but since Akka Persistence is based on the single-writer principle the persistent actors are typically used together
with Cluster Sharding. For a particular `persistenceId` only one persistent actor instance should be active at one time.
If multiple instances were to persist events at the same time, the events would be interleaved and might not be
interpreted correctly on replay. Cluster Sharding ensures that there is only one active entity for each id. The
@ref:[Cluster Sharding example](cluster-sharding.md#persistence-example) illustrates this common combination.

## Accessing the ActorContext

If the persistent behavior needs to use the `ActorContext`, for example to spawn child actors, it can be obtained by 
wrapping construction with `Behaviors.setup`:

Scala
:  @@snip [BasicPersistentBehaviorCompileOnly.scala](/akka-persistence-typed/src/test/scala/docs/akka/persistence/typed/BasicPersistentBehaviorCompileOnly.scala) { #actor-context }

Java
:  @@snip [BasicPersistentBehaviorTest.java](/akka-persistence-typed/src/test/java/jdocs/akka/persistence/typed/BasicPersistentBehaviorTest.java) { #actor-context }



## Changing Behavior

After processing a message, plain typed actors are able to return the `Behavior` that is used
for next message.

As you can see in the above examples this is not supported by typed persistent actors. Instead, the state is
returned by `eventHandler`. The reason a new behavior can't be returned is that behavior is part of the actor's
state and must also carefully be reconstructed during recovery. If it would have been supported it would mean
that the behavior must be restored when replaying events and also encoded in the state anyway when snapshots are used.
That would be very prone to mistakes and thus not allowed in Typed Persistence.

For basic actors you can use the same set of command handlers independent of what state the entity is in,
as shown in above example. For more complex actors it's useful to be able to change the behavior in the sense
that different functions for processing commands may be defined depending on what state the actor is in.
This is useful when implementing finite state machine (FSM) like entities.

The next example shows how to define different behavior based on the current `State`. It is an actor that
represents the state of a blog post. Before a post is started the only command it can process is to `AddPost`.
Once it is started then it we can look it up with `GetPost`, modify it with `ChangeBody` or publish it with `Publish`.

The state is captured by:

Scala
:  @@snip [BlogPostExample.scala](/akka-persistence-typed/src/test/scala/docs/akka/persistence/typed/BlogPostExample.scala) { #state }

Java
:  @@snip [BlogPostExample.java](/akka-persistence-typed/src/test/java/jdocs/akka/persistence/typed/BlogPostExample.java) { #state }

The commands, of which only a subset are valid depending on the state:

Scala
:  @@snip [BlogPostExample.scala](/akka-persistence-typed/src/test/scala/docs/akka/persistence/typed/BlogPostExample.scala) { #commands }

Java
:  @@snip [BlogPostExample.java](/akka-persistence-typed/src/test/java/jdocs/akka/persistence/typed/BlogPostExample.java) { #commands }

@java[The commandler handler to process each command is decided by the state class (or state predicate) that is
given to the `forStateType` of the `CommandHandlerBuilder` and the match cases in the builders.]
@scala[The command handler to process each command is decided by first looking at the state and then the command.
It typically becomes two levels of pattern matching, first on the state and then on the command.]
Delegating to methods is a good practise because the one-line cases give a nice overview of the message dispatch.

Scala
:  @@snip [BlogPostExample.scala](/akka-persistence-typed/src/test/scala/docs/akka/persistence/typed/BlogPostExample.scala) { #command-handler }

Java
:  @@snip [BlogPostExample.java](/akka-persistence-typed/src/test/java/jdocs/akka/persistence/typed/BlogPostExample.java) { #command-handler }


The event handler:

Scala
:  @@snip [BlogPostExample.scala](/akka-persistence-typed/src/test/scala/docs/akka/persistence/typed/BlogPostExample.scala) { #event-handler }

Java
:  @@snip [BlogPostExample.java](/akka-persistence-typed/src/test/java/jdocs/akka/persistence/typed/BlogPostExample.java) { #event-handler }

And finally the behavior is created @scala[from the `EventSourcedBehavior.apply`]:

Scala
:  @@snip [BlogPostExample.scala](/akka-persistence-typed/src/test/scala/docs/akka/persistence/typed/BlogPostExample.scala) { #behavior }

Java
:  @@snip [BlogPostExample.java](/akka-persistence-typed/src/test/java/jdocs/akka/persistence/typed/BlogPostExample.java) { #behavior }

This can be taken one or two steps further by defining the event and command handlers in the state class as
illustrated in @ref:[event handlers in the state](persistence-style.md#event-handlers-in-the-state) and
@ref:[command handlers in the state](persistence-style.md#command-handlers-in-the-state).

There is also an example illustrating an @ref:[optional initial state](persistence-style.md#optional-initial-state).

## Effects and Side Effects

Each command has a single `Effect` which can be:

* Persist events
* None: Accept the command but no effects
* Unhandled: Don't handle this command
* Stash: the current command is placed in a buffer and can be unstashed and processed later

Note that there is only one of these. It is not possible to both persist and say none/unhandled.
These are created using @java[a factory that is returned via the `Effect()` method]
@scala[the `Effect` factory] and once created additional `SideEffects` can be added.

Most of them time this will be done with the `thenRun` method on the `Effect` above. It is also possible
factor out common `SideEffect`s. For example:

Scala
:  @@snip [BasicPersistentBehaviorCompileOnly.scala](/akka-persistence-typed/src/test/scala/akka/persistence/typed/scaladsl/PersistentActorCompileOnlyTest.scala) { #commonChainedEffects }

Java
:  @@snip [BasicPersistentBehaviorCompileOnly.scala](/akka-persistence-typed/src/test/java/akka/persistence/typed/javadsl/PersistentActorCompileOnlyTest.java) { #commonChainedEffects }

### Side effects ordering and guarantees

Any `SideEffect`s are executed on an at-once basis and will not be executed if the persist fails.
The `SideEffect`s are executed sequentially, it is not possible to execute `SideEffect`s in parallel.

## Replies

The @ref:[Request-Response interaction pattern](interaction-patterns.md#request-response) is very common for
persistent actors, because you typically want to know if the command was rejected due to validation errors and
when accepted you want a confirmation when the events have been successfully stored.

Therefore you typically include a @scala[`ActorRef[ReplyMessageType]`]@java[`ActorRef<ReplyMessageType>`] in the
commands. After validation errors or after persisting events, using a `thenRun` side effect, the reply message can
be sent to the `ActorRef`.

Scala
:  @@snip [BlogPostExample.scala](/akka-persistence-typed/src/test/scala/docs/akka/persistence/typed/BlogPostExample.scala) { #reply-command }

Java
:  @@snip [BlogPostExample.java](/akka-persistence-typed/src/test/java/jdocs/akka/persistence/typed/BlogPostExample.java) { #reply-command }


Scala
:  @@snip [BlogPostExample.scala](/akka-persistence-typed/src/test/scala/docs/akka/persistence/typed/BlogPostExample.scala) { #reply }

Java
:  @@snip [BlogPostExample.java](/akka-persistence-typed/src/test/java/jdocs/akka/persistence/typed/BlogPostExample.java) { #reply }


Since this is such a common pattern there is a reply effect for this purpose. It has the nice property that
it can be used to enforce that replies are not forgotten when implementing the `EventSourcedBehavior`.
If it's defined with @scala[`EventSourcedBehavior.withEnforcedReplies`]@java[`EventSourcedBehaviorWithEnforcedReplies`]
there will be compilation errors if the returned effect isn't a `ReplyEffect`, which can be
created with @scala[`Effect.reply`]@java[`Effects().reply`], @scala[`Effect.noReply`]@java[`Effects().noReply`],
@scala[`Effect.thenReply`]@java[`Effects().thenReply`], or @scala[`Effect.thenNoReply`]@java[`Effects().thenNoReply`].

Scala
:  @@snip [AccountExampleWithEventHandlersInState.scala](/akka-persistence-typed/src/test/scala/docs/akka/persistence/typed/AccountExampleWithEventHandlersInState.scala) { #withEnforcedReplies }

Java
:  @@snip [AccountExampleWithNullState.java](/akka-persistence-typed/src/test/java/jdocs/akka/persistence/typed/AccountExampleWithEventHandlersInState.java) { #withEnforcedReplies }

The commands must implement `ExpectingReply` to include the @scala[`ActorRef[ReplyMessageType]`]@java[`ActorRef<ReplyMessageType>`]
in a standardized way.

Scala
:  @@snip [AccountExampleWithEventHandlersInState.scala](/akka-persistence-typed/src/test/scala/docs/akka/persistence/typed/AccountExampleWithEventHandlersInState.scala) { #reply-command }

Java
:  @@snip [AccountExampleWithNullState.java](/akka-persistence-typed/src/test/java/jdocs/akka/persistence/typed/AccountExampleWithEventHandlersInState.java) { #reply-command }

The `ReplyEffect` is created with @scala[`Effect.reply`]@java[`Effects().reply`], @scala[`Effect.noReply`]@java[`Effects().noReply`],
@scala[`Effect.thenReply`]@java[`Effects().thenReply`], or @scala[`Effect.thenNoReply`]@java[`Effects().thenNoReply`].

@java[Note that command handlers are defined with `newCommandHandlerWithReplyBuilder` when using
`EventSourcedBehaviorWithEnforcedReplies`], as opposed to newCommandHandlerBuilder when using `EventSourcedBehavior`.]

Scala
:  @@snip [AccountExampleWithEventHandlersInState.scala](/akka-persistence-typed/src/test/scala/docs/akka/persistence/typed/AccountExampleWithEventHandlersInState.scala) { #reply }

Java
:  @@snip [AccountExampleWithNullState.java](/akka-persistence-typed/src/test/java/jdocs/akka/persistence/typed/AccountExampleWithEventHandlersInState.java) { #reply }

These effects will send the reply message even when @scala[`EventSourcedBehavior.withEnforcedReplies`]@java[`EventSourcedBehaviorWithEnforcedReplies`]
is not used, but then there will be no compilation errors if the reply decision is left out.

Note that the `noReply` is a way of making conscious decision that a reply shouldn't be sent for a specific
command or the reply will be sent later, perhaps after some asynchronous interaction with other actors or services.

## Serialization

The same @ref:[serialization](../serialization.md) mechanism as for untyped
actors is also used in Akka Typed, also for persistent actors. When picking serialization solution for the events
you should also consider that it must be possible read old events when the application has evolved.
Strategies for that can be found in the @ref:[schema evolution](../persistence-schema-evolution.md).

## Recovery

It is strongly discouraged to perform side effects in `applyEvent`,
so side effects should be performed once recovery has completed as a reaction to the `RecoveryCompleted` signal @scala[`receiveSignal` handler] @java[by overriding `receiveSignal`]

Scala
:  @@snip [BasicPersistentBehaviorCompileOnly.scala](/akka-persistence-typed/src/test/scala/docs/akka/persistence/typed/BasicPersistentBehaviorCompileOnly.scala) { #recovery }

Java
:  @@snip [BasicPersistentBehaviorTest.java](/akka-persistence-typed/src/test/java/jdocs/akka/persistence/typed/BasicPersistentBehaviorTest.java) { #recovery }

The `RecoveryCompleted` contains the current `State`.

@ref[Snapshots)[persistence-snapshot.md] can be used for optimizing recovery times.

## Tagging

Persistence typed allows you to use event tags without using @ref[`EventAdapter`](../persistence.md#event-adapters):

Scala
:  @@snip [BasicPersistentActorCompileOnly.scala](/akka-persistence-typed/src/test/scala/docs/akka/persistence/typed/BasicPersistentBehaviorCompileOnly.scala) { #tagging }

Java
:  @@snip [BasicPersistentBehaviorTest.java](/akka-persistence-typed/src/test/java/jdocs/akka/persistence/typed/BasicPersistentBehaviorTest.java) { #tagging }

## Event adapters

Event adapters can be programmatically added to your `EventSourcedBehavior`s that can convert from your `Event` type
to another type that is then passed to the journal.

Defining an event adapter is done by extending an EventAdapter:

Scala
:  @@snip [x](/akka-persistence-typed/src/test/scala/akka/persistence/typed/scaladsl/EventSourcedBehaviorSpec.scala) { #event-wrapper }

Java
:  @@snip [x](/akka-persistence-typed/src/test/java/akka/persistence/typed/javadsl/PersistentActorCompileOnlyTest.java) { #event-wrapper }

Then install it on a persistent behavior:

Scala
:  @@snip [x](/akka-persistence-typed/src/test/scala/akka/persistence/typed/scaladsl/EventSourcedBehaviorSpec.scala) { #install-event-adapter }

Java
:  @@snip [x](/akka-persistence-typed/src/test/java/akka/persistence/typed/javadsl/PersistentActorCompileOnlyTest.java) { #install-event-adapter }

## Wrapping Persistent Behaviors

When creating a `EventSourcedBehavior`, it is possible to wrap `EventSourcedBehavior` in
other behaviors such as `Behaviors.setup` in order to access the `ActorContext` object. For instance
to access the actor logging upon taking snapshots for debug purpose.

Scala
:  @@snip [BasicPersistentActorCompileOnly.scala](/akka-persistence-typed/src/test/scala/docs/akka/persistence/typed/BasicPersistentBehaviorCompileOnly.scala) { #wrapPersistentBehavior }

Java
:  @@snip [BasicPersistentBehaviorTest.java](/akka-persistence-typed/src/test/java/jdocs/akka/persistence/typed/BasicPersistentBehaviorTest.java) { #wrapPersistentBehavior }


## Journal failures

By default a `EventSourcedBehavior` will stop if an exception is thrown from the journal. It is possible to override this with
any `BackoffSupervisorStrategy`. It is not possible to use the normal supervision wrapping for this as it isn't valid to
`resume` a behavior on a journal failure as it is not known if the event was persisted.


Scala
:  @@snip [BasicPersistentBehaviorSpec.scala](/akka-persistence-typed/src/test/scala/docs/akka/persistence/typed/BasicPersistentBehaviorCompileOnly.scala) { #supervision }

Java
:  @@snip [BasicPersistentBehaviorTest.java](/akka-persistence-typed/src/test/java/jdocs/akka/persistence/typed/BasicPersistentBehaviorTest.java) { #supervision }

## Journal rejections

Journals can reject events. The difference from a failure is that the journal must decide to reject an event before
trying to persist it e.g. because of a serialization exception. If an event is rejected it definitely won't be in the journal. 
This is signalled to a `EventSourcedBehavior` via a `EventRejectedException` and can be handled with a @ref[supervisor](fault-tolerance.md). 

## Stash

When persisting events with `persist` or `persistAll` it is guaranteed that the persistent actor will not receive
further commands until after the events have been confirmed to be persisted and additional side effects have been run.
Incoming messages are stashed automatically until the `persist` is completed.

Commands are also stashed during recovery and will not interfere with replayed events. Commands will be received
when recovery has been completed.

The stashing described above is handled automatically, but there is also a possibility to stash commands when
they are received to defer processing of them until later. One example could be waiting for some external condition
or interaction to complete before processing additional commands. That is accomplished by returning a `stash` effect
and later use `thenUnstashAll`.

Let's use an example of a task manager to illustrate how the stashing effects can be used. It handles three commands;
`StartTask`, `NextStep` and `EndTask`. Those commands are associated with a given `taskId` and the manager process
one `taskId` at a time. A task is started when receiving `StartTask`, and continues when receiving `NextStep` commands
until the final `EndTask` is received. Commands with another `taskId` than the one in progress are deferred by
stashing them. When `EndTask` is processed a new task can start and the stashed commands are processed.

Scala
:  @@snip [StashingExample.scala](/akka-persistence-typed/src/test/scala/docs/akka/persistence/typed/StashingExample.scala) { #stashing }

Java
:  @@snip [StashingExample.java](/akka-persistence-typed/src/test/java/jdocs/akka/persistence/typed/StashingExample.java) { #stashing }

You should be careful to not send more messages to a persistent actor than it can keep up with, otherwise the stash
buffer will fill up and when reaching its maximum capacity the commands will be dropped. The capacity can be configured with:

```
akka.persistence.typed.stash-capacity = 10000
```

Note that the stashed commands are kept in an in-memory buffer, so in case of a crash they will not be
processed.

* Stashed commands are discarded if the actor (entity) is passivated or rebalanced by Cluster Sharding.
* Stashed commands are discarded if the actor is restarted (or stopped) due to that an exception was thrown from processing a command or side effect after persisting.
* Stashed commands are preserved and processed later in case of failure in storing events if an `onPersistFailure` backoff supervisor strategy is defined.

It's allowed to stash messages while unstashing. Those newly added commands will not be processed by the
`unstashAll` effect that was in progress and have to be unstashed by another `unstashAll`.
