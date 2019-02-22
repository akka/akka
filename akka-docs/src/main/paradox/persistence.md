# Persistence

## Dependency

To use Akka Persistence, you must add the following dependency in your project:

@@dependency[sbt,Maven,Gradle] {
  group="com.typesafe.akka"
  artifact="akka-persistence_$scala.binary_version$"
  version="$akka.version$"
}

The Akka Persistence extension comes with few built-in persistence plugins, including
in-memory heap based journal, local file-system based snapshot-store and LevelDB based journal.

LevelDB-based plugins will require the following additional dependency:

@@dependency[sbt,Maven,Gradle] {
  group="org.fusesource.leveldbjni"
  artifact="leveldbjni-all"
  version="1.8"
}

## Sample project

You can look at the
@java[@extref[Persistence example project](samples:akka-samples-persistence-java)]
@scala[@extref[Persistence example project](samples:akka-samples-persistence-scala)]
to see what this looks like in practice.

## Introduction

Akka persistence enables stateful actors to persist their state so that it can be recovered when an actor
is either restarted, such as after a JVM crash, by a supervisor or a manual stop-start, or migrated within a cluster. The key concept behind Akka
persistence is that only the _events_ received by the actor are persisted, not the actual state of the actor
(though actor state snapshot support is also available). The events are persisted by appending to storage (nothing is ever mutated) which
allows for very high transaction rates and efficient replication. A stateful actor is recovered by replaying the stored
events to the actor, allowing it to rebuild its state. This can be either the full history of changes
or starting from a checkpoint in a snapshot which can dramatically reduce recovery times. Akka persistence also provides point-to-point
communication with at-least-once message delivery semantics.

@@@ note

The General Data Protection Regulation (GDPR) requires that personal information must be deleted at the request of users.
Deleting or modifying events that carry personal information would be difficult. Data shredding can be used to forget
information instead of deleting or modifying it. This is achieved by encrypting the data with a key for a given data
subject id (person) and deleting the key when that data subject is to be forgotten. Lightbend's
[GDPR for Akka Persistence](https://developer.lightbend.com/docs/akka-commercial-addons/current/gdpr/index.html)
provides tools to facilitate in building GDPR capable systems.

@@@

Akka persistence is inspired by and the official replacement of the [eventsourced](https://github.com/eligosource/eventsourced) library. It follows the same
concepts and architecture of [eventsourced](https://github.com/eligosource/eventsourced) but significantly differs on API and implementation level. See also
@ref:[migration-eventsourced-2.3](project/migration-guide-eventsourced-2.3.x.md)

## Architecture

 * @scala[`PersistentActor`]@java[`AbstractPersistentActor`]: Is a persistent, stateful actor. It is able to persist events to a journal and can react to
them in a thread-safe manner. It can be used to implement both *command* as well as *event sourced* actors.
When a persistent actor is started or restarted, journaled messages are replayed to that actor so that it can
recover its state from these messages.
 * @scala[`AtLeastOnceDelivery`]@java[`AbstractPersistentActorAtLeastOnceDelivery`]: To send messages with at-least-once delivery semantics to destinations, also in
case of sender and receiver JVM crashes.
 * `AsyncWriteJournal`: A journal stores the sequence of messages sent to a persistent actor. An application can control which messages
are journaled and which are received by the persistent actor without being journaled. Journal maintains `highestSequenceNr` that is increased on each message.
The storage backend of a journal is pluggable. The persistence extension comes with a "leveldb" journal plugin, which writes to the local filesystem.
Replicated journals are available as [Community plugins](http://akka.io/community/).
 * *Snapshot store*: A snapshot store persists snapshots of a persistent actor's state. Snapshots are
used for optimizing recovery times. The storage backend of a snapshot store is pluggable.
The persistence extension comes with a "local" snapshot storage plugin, which writes to the local filesystem. Replicated snapshot stores are available as [Community plugins](http://akka.io/community/)
 * *Event sourcing*. Based on the building blocks described above, Akka persistence provides abstractions for the
development of event sourced applications (see section [Event sourcing](#event-sourcing)).

<a id="event-sourcing"></a>
## Event sourcing

See an [introduction to EventSourcing](https://msdn.microsoft.com/en-us/library/jj591559.aspx), what follows is
Akka's implementation via persistent actors. 

A persistent actor receives a (non-persistent) command
which is first validated if it can be applied to the current state. Here validation can mean anything, from simple
inspection of a command message's fields up to a conversation with several external services, for example.
If validation succeeds, events are generated from the command, representing the effect of the command. These events
are then persisted and, after successful persistence, used to change the actor's state. When the persistent actor
needs to be recovered, only the persisted events are replayed of which we know that they can be successfully applied.
In other words, events cannot fail when being replayed to a persistent actor, in contrast to commands. Event sourced
actors may also process commands that do not change application state such as query commands for example.

Another excellent article about "thinking in Events" is [Events As First-Class Citizens](https://hackernoon.com/events-as-first-class-citizens-8633e8479493) by Randy Shoup. It is a short and recommended read if you're starting
developing Events based applications.

Akka persistence supports event sourcing with the @scala[`PersistentActor` trait]@java[`AbstractPersistentActor` abstract class]. An actor that extends this @scala[trait]@java[class] uses the
`persist` method to persist and handle events. The behavior of @scala[a `PersistentActor`]@java[an `AbstractPersistentActor`]
is defined by implementing @scala[`receiveRecover`]@java[`createReceiveRecover`] and @scala[`receiveCommand`]@java[`createReceive`]. This is demonstrated in the following example.

Scala
:  @@snip [PersistentActorExample.scala](/akka-docs/src/test/scala/docs/persistence/PersistentActorExample.scala) { #persistent-actor-example }

Java
:  @@snip [PersistentActorExample.java](/akka-docs/src/test/java/jdocs/persistence/PersistentActorExample.java) { #persistent-actor-example }

The example defines two data types, `Cmd` and `Evt` to represent commands and events, respectively. The
`state` of the `ExamplePersistentActor` is a list of persisted event data contained in `ExampleState`.

The persistent actor's @scala[`receiveRecover`]@java[`createReceiveRecover`] method defines how `state` is updated during recovery by handling `Evt`
and `SnapshotOffer` messages. The persistent actor's @scala[`receiveCommand`]@java[`createReceive`] method is a command handler. In this example,
a command is handled by generating an event which is then persisted and handled. Events are persisted by calling
`persist` with an event (or a sequence of events) as first argument and an event handler as second argument.

The `persist` method persists events asynchronously and the event handler is executed for successfully persisted
events. Successfully persisted events are internally sent back to the persistent actor as individual messages that trigger
event handler executions. An event handler may close over persistent actor state and mutate it. The sender of a persisted
event is the sender of the corresponding command. This allows event handlers to reply to the sender of a command
(not shown).

The main responsibility of an event handler is changing persistent actor state using event data and notifying others
about successful state changes by publishing events.

When persisting events with `persist` it is guaranteed that the persistent actor will not receive further commands between
the `persist` call and the execution(s) of the associated event handler. This also holds for multiple `persist`
calls in context of a single command. Incoming messages are [stashed](#internal-stash) until the `persist`
is completed.

If persistence of an event fails, `onPersistFailure` will be invoked (logging the error by default),
and the actor will unconditionally be stopped. If persistence of an event is rejected before it is
stored, e.g. due to serialization error, `onPersistRejected` will be invoked (logging a warning
by default) and the actor continues with the next message.

The easiest way to run this example yourself is to download the ready to run
@scala[@extref[Akka Persistence Sample with Scala](ecs:akka-samples-persistence-scala)]
@java[@extref[Akka Persistence Sample with Java](ecs:akka-samples-persistence-java)]
together with the tutorial. It contains instructions on how to run the `PersistentActorExample`.
The source code of this sample can be found in the @scala[@extref[Akka Samples Repository](samples:akka-samples-persistence-scala)]@java[@extref[Akka Samples Repository](samples:akka-samples-persistence-java)].

@@@ note

It's also possible to switch between different command handlers during normal processing and recovery
with @scala[`context.become()`]@java[`getContext().become()`] and @scala[`context.unbecome()`]@java[`getContext().unbecome()`]. To get the actor into the same state after
recovery you need to take special care to perform the same state transitions with `become` and
`unbecome` in the @scala[`receiveRecover`]@java[`createReceiveRecover`] method as you would have done in the command handler.
Note that when using `become` from @scala[`receiveRecover`]@java[`createReceiveRecover`] it will still only use the @scala[`receiveRecover`]@java[`createReceiveRecover`]
behavior when replaying the events. When replay is completed it will use the new behavior.

@@@

<a id="persistence-id"></a>
### Identifiers

A persistent actor must have an identifier that doesn't change across different actor incarnations.
The identifier must be defined with the `persistenceId` method.

Scala
:  @@snip [PersistenceDocSpec.scala](/akka-docs/src/test/scala/docs/persistence/PersistenceDocSpec.scala) { #persistence-id-override }

Java
:  @@snip [LambdaPersistenceDocTest.java](/akka-docs/src/test/java/jdocs/persistence/LambdaPersistenceDocTest.java) { #persistence-id-override }

@@@ note

`persistenceId` must be unique to a given entity in the journal (database table/keyspace).
When replaying messages persisted to the journal, you query messages with a `persistenceId`.
So, if two different entities share the same `persistenceId`, message-replaying
behavior is corrupted.

@@@

<a id="recovery"></a>
### Recovery

By default, a persistent actor is automatically recovered on start and on restart by replaying journaled messages.
New messages sent to a persistent actor during recovery do not interfere with replayed messages.
They are stashed and received by a persistent actor after recovery phase completes.

The number of concurrent recoveries that can be in progress at the same time is limited
to not overload the system and the backend data store. When exceeding the limit the actors will wait
until other recoveries have been completed. This is configured by:

```
akka.persistence.max-concurrent-recoveries = 50
```

@@@ note

Accessing the @scala[`sender()`]@java[sender with `getSender()`] for replayed messages will always result in a `deadLetters` reference,
as the original sender is presumed to be long gone. If you indeed have to notify an actor during
recovery in the future, store its `ActorPath` explicitly in your persisted events.

@@@

<a id="recovery-custom"></a>
#### Recovery customization

Applications may also customise how recovery is performed by returning a customised `Recovery` object
in the `recovery` method of a @scala[`PersistentActor`]@java[`AbstractPersistentActor`],

To skip loading snapshots and replay all events you can use @scala[`SnapshotSelectionCriteria.None`]@java[`SnapshotSelectionCriteria.none()`].
This can be useful if snapshot serialization format has changed in an incompatible way.
It should typically not be used when events have been deleted.

Scala
:  @@snip [PersistenceDocSpec.scala](/akka-docs/src/test/scala/docs/persistence/PersistenceDocSpec.scala) { #recovery-no-snap }

Java
:  @@snip [LambdaPersistenceDocTest.java](/akka-docs/src/test/java/jdocs/persistence/LambdaPersistenceDocTest.java) { #recovery-no-snap }

Another possible recovery customization, which can be useful for debugging, is setting an
upper bound on the replay, causing the actor to be replayed only up to a certain point "in the past" (instead of being replayed to its most up to date state). Note that after that it is a bad idea to persist new
events because a later recovery will probably be confused by the new events that follow the
events that were previously skipped.

Scala
:  @@snip [PersistenceDocSpec.scala](/akka-docs/src/test/scala/docs/persistence/PersistenceDocSpec.scala) { #recovery-custom }

Java
:  @@snip [LambdaPersistenceDocTest.java](/akka-docs/src/test/java/jdocs/persistence/LambdaPersistenceDocTest.java) { #recovery-custom }

Recovery can be disabled by returning `Recovery.none()` in the `recovery` method of a `PersistentActor`:

Scala
:  @@snip [PersistenceDocSpec.scala](/akka-docs/src/test/scala/docs/persistence/PersistenceDocSpec.scala) { #recovery-disabled }

Java
:  @@snip [LambdaPersistenceDocTest.java](/akka-docs/src/test/java/jdocs/persistence/LambdaPersistenceDocTest.java) { #recovery-disabled }

#### Recovery status

A persistent actor can query its own recovery status via the methods

Scala
:  @@snip [PersistenceDocSpec.scala](/akka-docs/src/test/scala/docs/persistence/PersistenceDocSpec.scala) { #recovery-status }

Java
:  @@snip [LambdaPersistenceDocTest.java](/akka-docs/src/test/java/jdocs/persistence/LambdaPersistenceDocTest.java) { #recovery-status }

Sometimes there is a need for performing additional initialization when the
recovery has completed before processing any other message sent to the persistent actor.
The persistent actor will receive a special `RecoveryCompleted` message right after recovery
and before any other received messages.

Scala
:  @@snip [PersistenceDocSpec.scala](/akka-docs/src/test/scala/docs/persistence/PersistenceDocSpec.scala) { #recovery-completed }

Java
:  @@snip [LambdaPersistenceDocTest.java](/akka-docs/src/test/java/jdocs/persistence/LambdaPersistenceDocTest.java) { #recovery-completed }

The actor will always receive a `RecoveryCompleted` message, even if there are no events
in the journal and the snapshot store is empty, or if it's a new persistent actor with a previously
unused `persistenceId`.

If there is a problem with recovering the state of the actor from the journal, `onRecoveryFailure`
is called (logging the error by default) and the actor will be stopped.

<a id="internal-stash"></a>
### Internal stash

The persistent actor has a private @ref:[stash](actors.md#stash) for internally caching incoming messages during
[recovery](#recovery) or the `persist\persistAll` method persisting events. You can still use/inherit from the
`Stash` interface. The internal stash cooperates with the normal stash by hooking into `unstashAll` method and
making sure messages are unstashed properly to the internal stash to maintain ordering guarantees.

You should be careful to not send more messages to a persistent actor than it can keep up with, otherwise the number
of stashed messages will grow without bounds. It can be wise to protect against `OutOfMemoryError` by defining a
maximum stash capacity in the mailbox configuration:

```
akka.actor.default-mailbox.stash-capacity=10000
```

Note that the stash capacity is per actor. If you have many persistent actors, e.g. when using cluster sharding,
you may need to define a small stash capacity to ensure that the total number of stashed messages in the system
doesn't consume too much memory. Additionally, the persistent actor defines three strategies to handle failure when the
internal stash capacity is exceeded. The default overflow strategy is the `ThrowOverflowExceptionStrategy`, which
discards the current received message and throws a `StashOverflowException`, causing actor restart if the default
supervision strategy is used. You can override the `internalStashOverflowStrategy` method to return
`DiscardToDeadLetterStrategy` or `ReplyToStrategy` for any "individual" persistent actor, or define the "default"
for all persistent actors by providing FQCN, which must be a subclass of `StashOverflowStrategyConfigurator`, in the
persistence configuration:

```
akka.persistence.internal-stash-overflow-strategy=
  "akka.persistence.ThrowExceptionConfigurator"
```

The `DiscardToDeadLetterStrategy` strategy also has a pre-packaged companion configurator
`akka.persistence.DiscardConfigurator`.

You can also query the default strategy via the Akka persistence extension singleton:    

Scala
:   @@@vars
    ```
    Persistence(context.system).defaultInternalStashOverflowStrategy
    ```
    @@@

Java
:   @@@vars
    ```
    Persistence.get(getContext().getSystem()).defaultInternalStashOverflowStrategy();
    ```
    @@@

@@@ note

The bounded mailbox should be avoided in the persistent actor, by which the messages come from storage backends may
be discarded. You can use bounded stash instead of it.

@@@

<a id="persist-async"></a>
### Relaxed local consistency requirements and high throughput use-cases

If faced with relaxed local consistency requirements and high throughput demands sometimes `PersistentActor` and its
`persist` may not be enough in terms of consuming incoming Commands at a high rate, because it has to wait until all
Events related to a given Command are processed in order to start processing the next Command. While this abstraction is
very useful for most cases, sometimes you may be faced with relaxed requirements about consistency – for example you may
want to process commands as fast as you can, assuming that the Event will eventually be persisted and handled properly in
the background, retroactively reacting to persistence failures if needed.

The `persistAsync` method provides a tool for implementing high-throughput persistent actors. It will *not*
stash incoming Commands while the Journal is still working on persisting and/or user code is executing event callbacks.

In the below example, the event callbacks may be called "at any time", even after the next Command has been processed.
The ordering between events is still guaranteed ("evt-b-1" will be sent after "evt-a-2", which will be sent after "evt-a-1" etc.).

Scala
:  @@snip [PersistenceDocSpec.scala](/akka-docs/src/test/scala/docs/persistence/PersistenceDocSpec.scala) { #persist-async }

Java
:  @@snip [LambdaPersistenceDocTest.java](/akka-docs/src/test/java/jdocs/persistence/LambdaPersistenceDocTest.java) { #persist-async }

@@@ note

In order to implement the pattern known as "*command sourcing*" call @scala[`persistAsync(cmd)(...)`]@java[`persistAsync`] right away on all incoming
messages and handle them in the callback.

@@@

@@@ warning

The callback will not be invoked if the actor is restarted (or stopped) in between the call to
`persistAsync` and the journal has confirmed the write.

@@@

<a id="defer"></a>
### Deferring actions until preceding persist handlers have executed

Sometimes when working with `persistAsync` or `persist` you may find that it would be nice to define some actions in terms of
''happens-after the previous `persistAsync`/`persist` handlers have been invoked''. `PersistentActor` provides utility methods
called `defer` and `deferAsync`, which work similarly to `persist` and `persistAsync` respectively yet do not persist the
passed in event. It is recommended to use them for *read* operations, and actions which do not have corresponding events in your
domain model.

Using those methods is very similar to the persist family of methods, yet they do **not** persist the passed in event.
It will be kept in memory and used when invoking the handler.

Scala
:  @@snip [PersistenceDocSpec.scala](/akka-docs/src/test/scala/docs/persistence/PersistenceDocSpec.scala) { #defer }

Java
:  @@snip [LambdaPersistenceDocTest.java](/akka-docs/src/test/java/jdocs/persistence/LambdaPersistenceDocTest.java) { #defer }

Notice that the `sender()` is **safe** to access in the handler callback, and will be pointing to the original sender
of the command for which this `defer` or `deferAsync` handler was called.

The calling side will get the responses in this (guaranteed) order:

Scala
:  @@snip [PersistenceDocSpec.scala](/akka-docs/src/test/scala/docs/persistence/PersistenceDocSpec.scala) { #defer-caller }

Java
:  @@snip [LambdaPersistenceDocTest.java](/akka-docs/src/test/java/jdocs/persistence/LambdaPersistenceDocTest.java) { #defer-caller }

You can also call `defer` or `deferAsync` with `persist`.

Scala
:  @@snip [PersistenceDocSpec.scala](/akka-docs/src/test/scala/docs/persistence/PersistenceDocSpec.scala) { #defer-with-persist }

Java
:  @@snip [LambdaPersistenceDocTest.java](/akka-docs/src/test/java/jdocs/persistence/LambdaPersistenceDocTest.java) { #defer-with-persist }

@@@ warning

The callback will not be invoked if the actor is restarted (or stopped) in between the call to
`defer` or `deferAsync` and the journal has processed and confirmed all preceding writes.

@@@

<a id="nested-persist-calls"></a>
### Nested persist calls

It is possible to call `persist` and `persistAsync` inside their respective callback blocks and they will properly
retain both the thread safety (including the right value of @scala[`sender()`]@java[`getSender()`]) as well as stashing guarantees.

In general it is encouraged to create command handlers which do not need to resort to nested event persisting,
however there are situations where it may be useful. It is important to understand the ordering of callback execution in
those situations, as well as their implication on the stashing behavior (that `persist()` enforces). In the following
example two persist calls are issued, and each of them issues another persist inside its callback:

Scala
:  @@snip [PersistenceDocSpec.scala](/akka-docs/src/test/scala/docs/persistence/PersistenceDocSpec.scala) { #nested-persist-persist }

Java
:  @@snip [LambdaPersistenceDocTest.java](/akka-docs/src/test/java/jdocs/persistence/LambdaPersistenceDocTest.java) { #nested-persist-persist }

When sending two commands to this `PersistentActor`, the persist handlers will be executed in the following order:

Scala
:  @@snip [PersistenceDocSpec.scala](/akka-docs/src/test/scala/docs/persistence/PersistenceDocSpec.scala) { #nested-persist-persist-caller }

Java
:  @@snip [LambdaPersistenceDocTest.java](/akka-docs/src/test/java/jdocs/persistence/LambdaPersistenceDocTest.java) { #nested-persist-persist-caller }

First the "outer layer" of persist calls is issued and their callbacks are applied. After these have successfully completed,
the inner callbacks will be invoked (once the events they are persisting have been confirmed to be persisted by the journal).
Only after all these handlers have been successfully invoked will the next command be delivered to the persistent Actor.
In other words, the stashing of incoming commands that is guaranteed by initially calling `persist()` on the outer layer
is extended until all nested `persist` callbacks have been handled.

It is also possible to nest `persistAsync` calls, using the same pattern:

Scala
:  @@snip [PersistenceDocSpec.scala](/akka-docs/src/test/scala/docs/persistence/PersistenceDocSpec.scala) { #nested-persistAsync-persistAsync }

Java
:  @@snip [LambdaPersistenceDocTest.java](/akka-docs/src/test/java/jdocs/persistence/LambdaPersistenceDocTest.java) { #nested-persistAsync-persistAsync }

In this case no stashing is happening, yet events are still persisted and callbacks are executed in the expected order:

Scala
:  @@snip [PersistenceDocSpec.scala](/akka-docs/src/test/scala/docs/persistence/PersistenceDocSpec.scala) { #nested-persistAsync-persistAsync-caller }

Java
:  @@snip [LambdaPersistenceDocTest.java](/akka-docs/src/test/java/jdocs/persistence/LambdaPersistenceDocTest.java) { #nested-persistAsync-persistAsync-caller }

While it is possible to nest mixed `persist` and `persistAsync` with keeping their respective semantics
it is not a recommended practice, as it may lead to overly complex nesting.

@@@ warning

While it is possible to nest `persist` calls within one another,
it is *not* legal call `persist` from any other Thread than the Actors message processing Thread.
For example, it is not legal to call `persist` from Futures! Doing so will break the guarantees
that the persist methods aim to provide. Always call `persist` and `persistAsync` from within
the Actor's receive block (or methods synchronously invoked from there).

@@@

<a id="failures"></a>
### Failures

If persistence of an event fails, `onPersistFailure` will be invoked (logging the error by default),
and the actor will unconditionally be stopped.

The reason that it cannot resume when persist fails is that it is unknown if the event was actually
persisted or not, and therefore it is in an inconsistent state. Restarting on persistent failures
will most likely fail anyway since the journal is probably unavailable. It is better to stop the
actor and after a back-off timeout start it again. The `akka.pattern.BackoffSupervisor` actor
is provided to support such restarts.

Scala
:  @@snip [PersistenceDocSpec.scala](/akka-docs/src/test/scala/docs/persistence/PersistenceDocSpec.scala) { #backoff }

Java
:  @@snip [LambdaPersistenceDocTest.java](/akka-docs/src/test/java/jdocs/persistence/LambdaPersistenceDocTest.java) { #backoff }

If persistence of an event is rejected before it is stored, e.g. due to serialization error,
`onPersistRejected` will be invoked (logging a warning by default), and the actor continues with
next message.

If there is a problem with recovering the state of the actor from the journal when the actor is
started, `onRecoveryFailure` is called (logging the error by default), and the actor will be stopped.
Note that failure to load snapshot is also treated like this, but you can disable loading of snapshots
if you for example know that serialization format has changed in an incompatible way, see [Recovery customization](#recovery-custom).

### Atomic writes

Each event is stored atomically, but it is also possible to store several events atomically by
using the `persistAll` or `persistAllAsync` method. That means that all events passed to that method
are stored or none of them are stored if there is an error.

The recovery of a persistent actor will therefore never be done partially with only a subset of events persisted by
*persistAll*.

Some journals may not support atomic writes of several events and they will then reject the `persistAll`
command, i.e. `onPersistRejected` is called with an exception (typically `UnsupportedOperationException`).

<a id="batch-writes"></a>
### Batch writes

In order to optimize throughput when using `persistAsync`, a persistent actor
internally batches events to be stored under high load before writing them to
the journal (as a single batch). The batch size is dynamically determined by
how many events are emitted during the time of a journal round-trip: after
sending a batch to the journal no further batch can be sent before confirmation
has been received that the previous batch has been written. Batch writes are never
timer-based which keeps latencies at a minimum.

### Message deletion

It is possible to delete all messages (journaled by a single persistent actor) up to a specified sequence number;
Persistent actors may call the `deleteMessages` method to this end.

Deleting messages in event sourcing based applications is typically either not used at all, or used in conjunction with
[snapshotting](#snapshots), i.e. after a snapshot has been successfully stored, a `deleteMessages(toSequenceNr)`
up until the sequence number of the data held by that snapshot can be issued to safely delete the previous events
while still having access to the accumulated state during replays - by loading the snapshot.

@@@ warning

If you are using @ref:[Persistence Query](persistence-query.md), query results may be missing deleted messages in a journal,
depending on how deletions are implemented in the journal plugin.
Unless you use a plugin which still shows deleted messages in persistence query results,
you have to design your application so that it is not affected by missing messages.

@@@

The result of the `deleteMessages` request is signaled to the persistent actor with a `DeleteMessagesSuccess`
message if the delete was successful or a `DeleteMessagesFailure` message if it failed.

Message deletion doesn't affect the highest sequence number of the journal, even if all messages were deleted from it after `deleteMessages` invocation.

### Persistence status handling

Persisting, deleting, and replaying messages can either succeed or fail.

|**Method**                 | **Success**            |
|---------------------------|------------------------|
|`persist` / `persistAsync` | persist handler invoked|
|`onPersistRejected`        | No automatic actions.  |
|`recovery`                 | `RecoveryCompleted`    |
|`deleteMessages`           | `DeleteMessagesSuccess`|

The most important operations (`persist` and `recovery`) have failure handlers modelled as explicit callbacks which
the user can override in the `PersistentActor`. The default implementations of these handlers emit a log message
(`error` for persist/recovery failures, and `warning` for others), logging the failure cause and information about
which message caused the failure.

For critical failures, such as recovery or persisting events failing, the persistent actor will be stopped after the failure
handler is invoked. This is because if the underlying journal implementation is signalling persistence failures it is most
likely either failing completely or overloaded and restarting right-away and trying to persist the event again will most
likely not help the journal recover – as it would likely cause a [Thundering herd problem](https://en.wikipedia.org/wiki/Thundering_herd_problem), as many persistent actors
would restart and try to persist their events again. Instead, using a `BackoffSupervisor` (as described in [Failures](#failures)) which
implements an exponential-backoff strategy which allows for more breathing room for the journal to recover between
restarts of the persistent actor.

@@@ note

Journal implementations may choose to implement a retry mechanism, e.g. such that only after a write fails N number
of times a persistence failure is signalled back to the user. In other words, once a journal returns a failure,
it is considered *fatal* by Akka Persistence, and the persistent actor which caused the failure will be stopped.

Check the documentation of the journal implementation you are using for details if/how it is using this technique.

@@@

<a id="safe-shutdown"></a>
### Safely shutting down persistent actors

Special care should be given when shutting down persistent actors from the outside.
With normal Actors it is often acceptable to use the special @ref:[PoisonPill](actors.md#poison-pill) message
to signal to an Actor that it should stop itself once it receives this message – in fact this message is handled
automatically by Akka, leaving the target actor no way to refuse stopping itself when given a poison pill.

This can be dangerous when used with `PersistentActor` due to the fact that incoming commands are *stashed* while
the persistent actor is awaiting confirmation from the Journal that events have been written when `persist()` was used.
Since the incoming commands will be drained from the Actor's mailbox and put into its internal stash while awaiting the
confirmation (thus, before calling the persist handlers) the Actor **may receive and (auto)handle the PoisonPill
before it processes the other messages which have been put into its stash**, causing a pre-mature shutdown of the Actor.

@@@ warning

Consider using explicit shut-down messages instead of `PoisonPill` when working with persistent actors.

@@@

The example below highlights how messages arrive in the Actor's mailbox and how they interact with its internal stashing
mechanism when `persist()` is used. Notice the early stop behavior that occurs when `PoisonPill` is used:

Scala
:  @@snip [PersistenceDocSpec.scala](/akka-docs/src/test/scala/docs/persistence/PersistenceDocSpec.scala) { #safe-shutdown }

Java
:  @@snip [LambdaPersistenceDocTest.java](/akka-docs/src/test/java/jdocs/persistence/LambdaPersistenceDocTest.java) { #safe-shutdown }


Scala
:  @@snip [PersistenceDocSpec.scala](/akka-docs/src/test/scala/docs/persistence/PersistenceDocSpec.scala) { #safe-shutdown-example-bad }

Java
:  @@snip [LambdaPersistenceDocTest.java](/akka-docs/src/test/java/jdocs/persistence/LambdaPersistenceDocTest.java) { #safe-shutdown-example-bad }


Scala
:  @@snip [PersistenceDocSpec.scala](/akka-docs/src/test/scala/docs/persistence/PersistenceDocSpec.scala) { #safe-shutdown-example-good }

Java
:  @@snip [LambdaPersistenceDocTest.java](/akka-docs/src/test/java/jdocs/persistence/LambdaPersistenceDocTest.java) { #safe-shutdown-example-good }

<a id="replay-filter"></a>
### Replay Filter

There could be cases where event streams are corrupted and multiple writers (i.e. multiple persistent actor instances)
journaled different messages with the same sequence number.
In such a case, you can configure how you filter replayed messages from multiple writers, upon recovery.

In your configuration, under the `akka.persistence.journal.xxx.replay-filter` section (where `xxx` is your journal plugin id),
you can select the replay filter `mode` from one of the following values:

 * repair-by-discard-old
 * fail
 * warn
 * off

For example, if you configure the replay filter for leveldb plugin, it looks like this:

```
# The replay filter can detect a corrupt event stream by inspecting
# sequence numbers and writerUuid when replaying events.
akka.persistence.journal.leveldb.replay-filter {
  # What the filter should do when detecting invalid events.
  # Supported values:
  # `repair-by-discard-old` : discard events from old writers,
  #                           warning is logged
  # `fail` : fail the replay, error is logged
  # `warn` : log warning but emit events untouched
  # `off` : disable this feature completely
  mode = repair-by-discard-old
}
```

<a id="snapshots"></a>
## Snapshots

As you model your domain using actors, you may notice that some actors may be prone to accumulating extremely long event logs and experiencing long recovery times. Sometimes, the right approach may be to split out into a set of shorter lived actors. However, when this is not an option, you can use snapshots to reduce recovery times drastically.

Persistent actors can save snapshots of internal state by calling the  `saveSnapshot` method. If saving of a snapshot
succeeds, the persistent actor receives a `SaveSnapshotSuccess` message, otherwise a `SaveSnapshotFailure` message

Scala
:  @@snip [PersistenceDocSpec.scala](/akka-docs/src/test/scala/docs/persistence/PersistenceDocSpec.scala) { #save-snapshot }

Java
:  @@snip [LambdaPersistenceDocTest.java](/akka-docs/src/test/java/jdocs/persistence/LambdaPersistenceDocTest.java) { #save-snapshot }

where `metadata` is of type `SnapshotMetadata`:

@@snip [SnapshotProtocol.scala](/akka-persistence/src/main/scala/akka/persistence/SnapshotProtocol.scala) { #snapshot-metadata }

During recovery, the persistent actor is offered the latest saved snapshot via a `SnapshotOffer` message from
which it can initialize internal state.

Scala
:  @@snip [PersistenceDocSpec.scala](/akka-docs/src/test/scala/docs/persistence/PersistenceDocSpec.scala) { #snapshot-offer }

Java
:  @@snip [LambdaPersistenceDocTest.java](/akka-docs/src/test/java/jdocs/persistence/LambdaPersistenceDocTest.java) { #snapshot-offer }

The replayed messages that follow the `SnapshotOffer` message, if any, are younger than the offered snapshot.
They finally recover the persistent actor to its current (i.e. latest) state.

In general, a persistent actor is only offered a snapshot if that persistent actor has previously saved one or more snapshots
and at least one of these snapshots matches the `SnapshotSelectionCriteria` that can be specified for recovery.

Scala
:  @@snip [PersistenceDocSpec.scala](/akka-docs/src/test/scala/docs/persistence/PersistenceDocSpec.scala) { #snapshot-criteria }

Java
:  @@snip [LambdaPersistenceDocTest.java](/akka-docs/src/test/java/jdocs/persistence/LambdaPersistenceDocTest.java) { #snapshot-criteria }

If not specified, they default to @scala[`SnapshotSelectionCriteria.Latest`]@java[`SnapshotSelectionCriteria.latest()`] which selects the latest (= youngest) snapshot.
To disable snapshot-based recovery, applications should use @scala[`SnapshotSelectionCriteria.None`]@java[`SnapshotSelectionCriteria.none()`]. A recovery where no
saved snapshot matches the specified `SnapshotSelectionCriteria` will replay all journaled messages.

@@@ note

In order to use snapshots, a default snapshot-store (`akka.persistence.snapshot-store.plugin`) must be configured,
or the @scala[`PersistentActor`]@java[persistent actor] can pick a snapshot store explicitly by overriding @scala[`def snapshotPluginId: String`]@java[`String snapshotPluginId()`].

Because some use cases may not benefit from or need snapshots, it is perfectly valid not to not configure a snapshot store.
However, Akka will log a warning message when this situation is detected and then continue to operate until
an actor tries to store a snapshot, at which point the operation will fail (by replying with an `SaveSnapshotFailure` for example).

Note that the "persistence mode" of @ref:[Cluster Sharding](cluster-sharding.md) makes use of snapshots. If you use that mode, you'll need to define a snapshot store plugin.

@@@

### Snapshot deletion

A persistent actor can delete individual snapshots by calling the `deleteSnapshot` method with the sequence number of
when the snapshot was taken.

To bulk-delete a range of snapshots matching `SnapshotSelectionCriteria`,
persistent actors should use the `deleteSnapshots` method. Depending on the journal used this might be inefficient. It is 
best practice to do specific deletes with `deleteSnapshot` or to include a `minSequenceNr` as well as a `maxSequenceNr`
for the `SnapshotSelectionCriteria`.

### Snapshot status handling

Saving or deleting snapshots can either succeed or fail – this information is reported back to the persistent actor via
status messages as illustrated in the following table.

|**Method**                                   | **Success**              | **Failure message**     |
|---------------------------------------------|--------------------------|-------------------------|
|`saveSnapshot(Any)`                          | `SaveSnapshotSuccess`    | `SaveSnapshotFailure`   |
|`deleteSnapshot(Long)`                       | `DeleteSnapshotSuccess`  | `DeleteSnapshotFailure` |
|`deleteSnapshots(SnapshotSelectionCriteria)` | `DeleteSnapshotsSuccess` | `DeleteSnapshotsFailure`|

If failure messages are left unhandled by the actor, a default warning log message will be logged for each incoming failure message.
No default action is performed on the success messages, however you're free to handle them e.g. in order to delete
an in memory representation of the snapshot, or in the case of failure to attempt save the snapshot again.


## Scaling out

In a use case where the number of persistent actors needed are higher than what would fit in the memory of one node or
where resilience is important so that if a node crashes the persistent actors are quickly started on a new node and can
resume operations @ref:[Cluster Sharding](cluster-sharding.md) is an excellent fit to spread persistent actors over a 
cluster and address them by id.

Akka Persistence is based on the single-writer principle. For a particular `persistenceId` only one `PersistentActor`
instance should be active at one time. If multiple instances were to persist events at the same time, the events would
be interleaved and might not be interpreted correctly on replay. Cluster Sharding ensures that there is only one
active entity (`PersistentActor`) for each id within a data center. Lightbend's
[Multi-DC Persistence](https://developer.lightbend.com/docs/akka-commercial-addons/current/persistence-dc/index.html)
supports active-active persistent entities across data centers.

The [Lagom framework](https://www.lagomframework.com), which is built on top of Akka encodes many of the best practices 
around this. For more details see @java[[Managing Data Persistence](https://www.lagomframework.com/documentation/current/java/ES_CQRS.html)]
@scala[[Managing Data Persistence](https://www.lagomframework.com/documentation/current/scala/ES_CQRS.html)] and 
@java[[Persistent Entity](https://www.lagomframework.com/documentation/current/java/PersistentEntity.html)] 
@scala[[Persistent Entity](https://www.lagomframework.com/documentation/current/scala/PersistentEntity.html)] in the Lagom documentation.

<a id="at-least-once-delivery"></a>
## At-Least-Once Delivery

To send messages with at-least-once delivery semantics to destinations you can @scala[mix-in `AtLeastOnceDelivery` trait to your `PersistentActor`]@java[extend the `AbstractPersistentActorWithAtLeastOnceDelivery` class instead of `AbstractPersistentActor`]
on the sending side.  It takes care of re-sending messages when they
have not been confirmed within a configurable timeout.

The state of the sending actor, including which messages have been sent that have not been
confirmed by the recipient must be persistent so that it can survive a crash of the sending actor
or JVM. The @scala[`AtLeastOnceDelivery` trait]@java[`AbstractPersistentActorWithAtLeastOnceDelivery` class] does not persist anything by itself. It is your
responsibility to persist the intent that a message is sent and that a confirmation has been
received.

@@@ note

At-least-once delivery implies that original message sending order is not always preserved,
and the destination may receive duplicate messages.
Semantics do not match those of a normal `ActorRef` send operation:

 * it is not at-most-once delivery
 * message order for the same sender–receiver pair is not preserved due to
possible resends
 * after a crash and restart of the destination messages are still
delivered to the new actor incarnation

These semantics are similar to what an `ActorPath` represents (see
@ref:[Actor Lifecycle](actors.md#actor-lifecycle)), therefore you need to supply a path and not a
reference when delivering messages. The messages are sent to the path with
an actor selection.

@@@

Use the `deliver` method to send a message to a destination. Call the `confirmDelivery` method
when the destination has replied with a confirmation message.

### Relationship between deliver and confirmDelivery

To send messages to the destination path, use the `deliver` method after you have persisted the intent
to send the message.

The destination actor must send back a confirmation message. When the sending actor receives this
confirmation message you should persist the fact that the message was delivered successfully and then call
the `confirmDelivery` method.

If the persistent actor is not currently recovering, the `deliver` method will send the message to
the destination actor. When recovering, messages will be buffered until they have been confirmed using `confirmDelivery`.
Once recovery has completed, if there are outstanding messages that have not been confirmed (during the message replay),
the persistent actor will resend these before sending any other messages.

Deliver requires a `deliveryIdToMessage` function to pass the provided `deliveryId` into the message so that the correlation
between `deliver` and `confirmDelivery` is possible. The `deliveryId` must do the round trip. Upon receipt
of the message, the destination actor will send the same``deliveryId`` wrapped in a confirmation message back to the sender.
The sender will then use it to call `confirmDelivery` method to complete the delivery routine.

Scala
:  @@snip [PersistenceDocSpec.scala](/akka-docs/src/test/scala/docs/persistence/PersistenceDocSpec.scala) { #at-least-once-example }

Java
:  @@snip [LambdaPersistenceDocTest.java](/akka-docs/src/test/java/jdocs/persistence/LambdaPersistenceDocTest.java) { #at-least-once-example }

The `deliveryId` generated by the persistence module is a strictly monotonically increasing sequence number
without gaps. The same sequence is used for all destinations of the actor, i.e. when sending to multiple
destinations the destinations will see gaps in the sequence. It is not possible to use custom `deliveryId`.
However, you can send a custom correlation identifier in the message to the destination. You must then retain
a mapping between the internal `deliveryId` (passed into the `deliveryIdToMessage` function) and your custom
correlation id (passed into the message). You can do this by storing such mapping in a `Map(correlationId -> deliveryId)`
from which you can retrieve the `deliveryId` to be passed into the `confirmDelivery` method once the receiver
of your message has replied with your custom correlation id.

The @scala[`AtLeastOnceDelivery` trait]@java[`AbstractPersistentActorWithAtLeastOnceDelivery` class] has a state consisting of unconfirmed messages and a
sequence number. It does not store this state itself. You must persist events corresponding to the
`deliver` and `confirmDelivery` invocations from your `PersistentActor` so that the state can
be restored by calling the same methods during the recovery phase of the `PersistentActor`. Sometimes
these events can be derived from other business level events, and sometimes you must create separate events.
During recovery, calls to `deliver` will not send out messages, those will be sent later
if no matching `confirmDelivery` will have been performed.

Support for snapshots is provided by `getDeliverySnapshot` and `setDeliverySnapshot`.
The `AtLeastOnceDeliverySnapshot` contains the full delivery state, including unconfirmed messages.
If you need a custom snapshot for other parts of the actor state you must also include the
`AtLeastOnceDeliverySnapshot`. It is serialized using protobuf with the ordinary Akka
serialization mechanism. It is easiest to include the bytes of the `AtLeastOnceDeliverySnapshot`
as a blob in your custom snapshot.

The interval between redelivery attempts is defined by the `redeliverInterval` method.
The default value can be configured with the `akka.persistence.at-least-once-delivery.redeliver-interval`
configuration key. The method can be overridden by implementation classes to return non-default values.

The maximum number of messages that will be sent at each redelivery burst is defined by the
`redeliveryBurstLimit` method (burst frequency is half of the redelivery interval). If there's a lot of
unconfirmed messages (e.g. if the destination is not available for a long time), this helps to prevent an overwhelming
amount of messages to be sent at once. The default value can be configured with the
`akka.persistence.at-least-once-delivery.redelivery-burst-limit` configuration key. The method can be overridden
by implementation classes to return non-default values.

After a number of delivery attempts a `AtLeastOnceDelivery.UnconfirmedWarning` message
will be sent to `self`. The re-sending will still continue, but you can choose to call
`confirmDelivery` to cancel the re-sending. The number of delivery attempts before emitting the
warning is defined by the `warnAfterNumberOfUnconfirmedAttempts` method. The default value can be
configured with the `akka.persistence.at-least-once-delivery.warn-after-number-of-unconfirmed-attempts`
configuration key. The method can be overridden by implementation classes to return non-default values.

The @scala[`AtLeastOnceDelivery` trait]@java[`AbstractPersistentActorWithAtLeastOnceDelivery` class] holds messages in memory until their successful delivery has been confirmed.
The maximum number of unconfirmed messages that the actor is allowed to hold in memory
is defined by the `maxUnconfirmedMessages` method. If this limit is exceed the `deliver` method will
not accept more messages and it will throw `AtLeastOnceDelivery.MaxUnconfirmedMessagesExceededException`.
The default value can be configured with the `akka.persistence.at-least-once-delivery.max-unconfirmed-messages`
configuration key. The method can be overridden by implementation classes to return non-default values.

<a id="event-adapters"></a>
## Event Adapters

In long running projects using event sourcing sometimes the need arises to detach the data model from the domain model
completely.

Event Adapters help in situations where:

 * **Version Migrations** – existing events stored in *Version 1* should be "upcasted" to a new *Version 2* representation,
and the process of doing so involves actual code, not just changes on the serialization layer. For these scenarios
the `toJournal` function is usually an identity function, however the `fromJournal` is implemented as
`v1.Event=>v2.Event`, performing the necessary mapping inside the fromJournal method.
This technique is sometimes referred to as "upcasting" in other CQRS libraries.
 * **Separating Domain and Data models** – thanks to EventAdapters it is possible to completely separate the domain model
from the model used to persist data in the Journals. For example one may want to use case classes in the
domain model, however persist their protocol-buffer (or any other binary serialization format) counter-parts to the Journal.
A simple `toJournal:MyModel=>MyDataModel` and `fromJournal:MyDataModel=>MyModel` adapter can be used to implement this feature.
 * **Journal Specialized Data Types** – exposing data types understood by the underlying Journal, for example for data stores which
understand JSON it is possible to write an EventAdapter `toJournal:Any=>JSON` such that the Journal can *directly* store the
json instead of serializing the object to its binary representation.

Implementing an EventAdapter is rather stright forward:

Scala
:  @@snip [PersistenceEventAdapterDocSpec.scala](/akka-docs/src/test/scala/docs/persistence/PersistenceEventAdapterDocSpec.scala) { #identity-event-adapter }

Java
:  @@snip [PersistenceEventAdapterDocTest.java](/akka-docs/src/test/java/jdocs/persistence/PersistenceEventAdapterDocTest.java) { #identity-event-adapter }

Then in order for it to be used on events coming to and from the journal you must bind it using the below configuration syntax:

@@snip [PersistenceEventAdapterDocSpec.scala](/akka-docs/src/test/scala/docs/persistence/PersistenceEventAdapterDocSpec.scala) { #event-adapters-config }

It is possible to bind multiple adapters to one class *for recovery*, in which case the `fromJournal` methods of all
bound adapters will be applied to a given matching event (in order of definition in the configuration). Since each adapter may
return from `0` to `n` adapted events (called as `EventSeq`), each adapter can investigate the event and if it should
indeed adapt it return the adapted event(s) for it. Other adapters which do not have anything to contribute during this
adaptation simply return `EventSeq.empty`. The adapted events are then delivered in-order to the `PersistentActor` during replay.

@@@ note

For more advanced schema evolution techniques refer to the @ref:[Persistence - Schema Evolution](persistence-schema-evolution.md) documentation.

@@@

<a id="persistent-fsm"></a>

<a id="storage-plugins"></a>
## Storage plugins

Storage backends for journals and snapshot stores are pluggable in the Akka persistence extension.

A directory of persistence journal and snapshot store plugins is available at the Akka Community Projects page, see [Community plugins](http://akka.io/community/)

Plugins can be selected either by "default" for all persistent actors,
or "individually", when a persistent actor defines its own set of plugins.

When a persistent actor does NOT override the `journalPluginId` and `snapshotPluginId` methods,
the persistence extension will use the "default" journal and snapshot-store plugins configured in `reference.conf`:

```
akka.persistence.journal.plugin = ""
akka.persistence.snapshot-store.plugin = ""
```

However, these entries are provided as empty "", and require explicit user configuration via override in the user `application.conf`.
For an example of a journal plugin which writes messages to LevelDB see [Local LevelDB journal](#local-leveldb-journal).
For an example of a snapshot store plugin which writes snapshots as individual files to the local filesystem see [Local snapshot store](#local-snapshot-store).

Applications can provide their own plugins by implementing a plugin API and activating them by configuration.
Plugin development requires the following imports:

Scala
:  @@snip [PersistencePluginDocSpec.scala](/akka-docs/src/test/scala/docs/persistence/PersistencePluginDocSpec.scala) { #plugin-imports }

Java
:  @@snip [LambdaPersistencePluginDocTest.java](/akka-docs/src/test/java/jdocs/persistence/LambdaPersistencePluginDocTest.java) { #plugin-imports }

### Eager initialization of persistence plugin

By default, persistence plugins are started on-demand, as they are used. In some case, however, it might be beneficial
to start a certain plugin eagerly. In order to do that, you should first add `akka.persistence.Persistence`
under the `akka.extensions` key. Then, specify the IDs of plugins you wish to start automatically under
`akka.persistence.journal.auto-start-journals` and `akka.persistence.snapshot-store.auto-start-snapshot-stores`.

For example, if you want eager initialization for the leveldb journal plugin and the local snapshot store plugin, your configuration should look like this:  

```
akka {

  extensions = [akka.persistence.Persistence]

  persistence {

    journal {
      plugin = "akka.persistence.journal.leveldb"
      auto-start-journals = ["akka.persistence.journal.leveldb"]
    }

    snapshot-store {
      plugin = "akka.persistence.snapshot-store.local"
      auto-start-snapshot-stores = ["akka.persistence.snapshot-store.local"]
    }

  }

}
```

<a id="pre-packaged-plugins"></a>
## Pre-packaged plugins

<a id="local-leveldb-journal"></a>
### Local LevelDB journal

The LevelDB journal plugin config entry is `akka.persistence.journal.leveldb`. It writes messages to a local LevelDB
instance. Enable this plugin by defining config property:

@@snip [PersistencePluginDocSpec.scala](/akka-docs/src/test/scala/docs/persistence/PersistencePluginDocSpec.scala) { #leveldb-plugin-config }

LevelDB based plugins will also require the following additional dependency declaration:

@@dependency[sbt,Maven,Gradle] {
  group="org.fusesource.leveldbjni"
  artifact="leveldbjni-all"
  version="1.8"
}

The default location of LevelDB files is a directory named `journal` in the current working
directory. This location can be changed by configuration where the specified path can be relative or absolute:

@@snip [PersistencePluginDocSpec.scala](/akka-docs/src/test/scala/docs/persistence/PersistencePluginDocSpec.scala) { #journal-config }

With this plugin, each actor system runs its own private LevelDB instance.

One peculiarity of LevelDB is that the deletion operation does not remove messages from the journal, but adds
a "tombstone" for each deleted message instead. In the case of heavy journal usage, especially one including frequent
deletes, this may be an issue as users may find themselves dealing with continuously increasing journal sizes. To
this end, LevelDB offers a special journal compaction function that is exposed via the following configuration:

@@snip [PersistencePluginDocSpec.scala](/akka-docs/src/test/scala/docs/persistence/PersistencePluginDocSpec.scala) { #compaction-intervals-config }

<a id="shared-leveldb-journal"></a>
### Shared LevelDB journal

A LevelDB instance can also be shared by multiple actor systems (on the same or on different nodes). This, for
example, allows persistent actors to failover to a backup node and continue using the shared journal instance from the
backup node.

@@@ warning

A shared LevelDB instance is a single point of failure and should therefore only be used for testing
purposes. Highly-available, replicated journals are available as [Community plugins](http://akka.io/community/).

@@@

@@@ note

This plugin has been supplanted by [Persistence Plugin Proxy](#persistence-plugin-proxy).

@@@

A shared LevelDB instance is started by instantiating the `SharedLeveldbStore` actor.

Scala
:  @@snip [PersistencePluginDocSpec.scala](/akka-docs/src/test/scala/docs/persistence/PersistencePluginDocSpec.scala) { #shared-store-creation }

Java
:  @@snip [LambdaPersistencePluginDocTest.java](/akka-docs/src/test/java/jdocs/persistence/LambdaPersistencePluginDocTest.java) { #shared-store-creation }

By default, the shared instance writes journaled messages to a local directory named `journal` in the current
working directory. The storage location can be changed by configuration:

@@snip [PersistencePluginDocSpec.scala](/akka-docs/src/test/scala/docs/persistence/PersistencePluginDocSpec.scala) { #shared-store-config }

Actor systems that use a shared LevelDB store must activate the `akka.persistence.journal.leveldb-shared`
plugin.

@@snip [PersistencePluginDocSpec.scala](/akka-docs/src/test/scala/docs/persistence/PersistencePluginDocSpec.scala) { #shared-journal-config }

This plugin must be initialized by injecting the (remote) `SharedLeveldbStore` actor reference. Injection is
done by calling the `SharedLeveldbJournal.setStore` method with the actor reference as argument.

Scala
:  @@snip [PersistencePluginDocSpec.scala](/akka-docs/src/test/scala/docs/persistence/PersistencePluginDocSpec.scala) { #shared-store-usage }

Java
:  @@snip [LambdaPersistencePluginDocTest.java](/akka-docs/src/test/java/jdocs/persistence/LambdaPersistencePluginDocTest.java) { #shared-store-usage }

Internal journal commands (sent by persistent actors) are buffered until injection completes. Injection is idempotent
i.e. only the first injection is used.

<a id="local-snapshot-store"></a>
### Local snapshot store

The local snapshot store plugin config entry is `akka.persistence.snapshot-store.local`. It writes snapshot files to
the local filesystem. Enable this plugin by defining config property:

@@snip [PersistencePluginDocSpec.scala](/akka-docs/src/test/scala/docs/persistence/PersistencePluginDocSpec.scala) { #leveldb-snapshot-plugin-config }

The default storage location is a directory named `snapshots` in the current working
directory. This can be changed by configuration where the specified path can be relative or absolute:

@@snip [PersistencePluginDocSpec.scala](/akka-docs/src/test/scala/docs/persistence/PersistencePluginDocSpec.scala) { #snapshot-config }

Note that it is not mandatory to specify a snapshot store plugin. If you don't use snapshots
you don't have to configure it.

<a id="persistence-plugin-proxy"></a>
### Persistence Plugin Proxy

A persistence plugin proxy allows sharing of journals and snapshot stores across multiple actor systems (on the same or
on different nodes). This, for example, allows persistent actors to failover to a backup node and continue using the
shared journal instance from the backup node. The proxy works by forwarding all the journal/snapshot store messages to a
single, shared, persistence plugin instance, and therefore supports any use case supported by the proxied plugin.

@@@ warning

A shared journal/snapshot store is a single point of failure and should therefore only be used for testing
purposes. Highly-available, replicated persistence plugins are available as [Community plugins](http://akka.io/community/).

@@@

The journal and snapshot store proxies are controlled via the `akka.persistence.journal.proxy` and
`akka.persistence.snapshot-store.proxy` configuration entries, respectively. Set the `target-journal-plugin` or
`target-snapshot-store-plugin` keys to the underlying plugin you wish to use (for example:
`akka.persistence.journal.leveldb`). The `start-target-journal` and `start-target-snapshot-store` keys should be
set to `on` in exactly one actor system - this is the system that will instantiate the shared persistence plugin.
Next, the proxy needs to be told how to find the shared plugin. This can be done by setting the `target-journal-address`
and `target-snapshot-store-address` configuration keys, or programmatically by calling the
`PersistencePluginProxy.setTargetLocation` method.

@@@ note

Akka starts extensions lazily when they are required, and this includes the proxy. This means that in order for the
proxy to work, the persistence plugin on the target node must be instantiated. This can be done by instantiating the
`PersistencePluginProxyExtension` @ref:[extension](extending-akka.md), or by calling the `PersistencePluginProxy.start` method.

@@@

@@@ note

The proxied persistence plugin can (and should) be configured using its original configuration keys.

@@@

<a id="custom-serialization"></a>
## Custom serialization

Serialization of snapshots and payloads of `Persistent` messages is configurable with Akka's
@ref:[Serialization](serialization.md) infrastructure. For example, if an application wants to serialize

 * payloads of type `MyPayload` with a custom `MyPayloadSerializer` and
 * snapshots of type `MySnapshot` with a custom `MySnapshotSerializer`

it must add

@@snip [PersistenceSerializerDocSpec.scala](/akka-docs/src/test/scala/docs/persistence/PersistenceSerializerDocSpec.scala) { #custom-serializer-config }

to the application configuration. If not specified, a default serializer is used.

For more advanced schema evolution techniques refer to the @ref:[Persistence - Schema Evolution](persistence-schema-evolution.md) documentation.

## Testing

When running tests with LevelDB default settings in `sbt`, make sure to set `fork := true` in your sbt project. Otherwise, you'll see an `UnsatisfiedLinkError`. Alternatively, you can switch to a LevelDB Java port by setting

@@snip [PersistencePluginDocSpec.scala](/akka-docs/src/test/scala/docs/persistence/PersistencePluginDocSpec.scala) { #native-config }

or

@@snip [PersistencePluginDocSpec.scala](/akka-docs/src/test/scala/docs/persistence/PersistencePluginDocSpec.scala) { #shared-store-native-config }

in your Akka configuration. The LevelDB Java port is for testing purposes only.

Also note that for the LevelDB Java port, you will need the following dependencies:

@@dependency[sbt,Maven,Gradle] {
  group="org.iq80.leveldb"
  artifact="leveldb"
  version="0.9"
}

@@@ warning

It is not possible to test persistence provided classes (i.e. [PersistentActor](#event-sourcing)
and [AtLeastOnceDelivery](#at-least-once-delivery)) using `TestActorRef` due to its *synchronous* nature.
These traits need to be able to perform asynchronous tasks in the background in order to handle internal persistence
related events.

When testing Persistence based projects always rely on @ref:[asynchronous messaging using the TestKit](testing.md#async-integration-testing).

@@@

## Configuration

There are several configuration properties for the persistence module, please refer
to the @ref:[reference configuration](general/configuration.md#config-akka-persistence).

## Multiple persistence plugin configurations

By default, a persistent actor will use the "default" journal and snapshot store plugins
configured in the following sections of the `reference.conf` configuration resource:

@@snip [PersistenceMultiDocSpec.scala](/akka-docs/src/test/scala/docs/persistence/PersistenceMultiDocSpec.scala) { #default-config }

Note that in this case the actor overrides only the `persistenceId` method:

Scala
:  @@snip [PersistenceMultiDocSpec.scala](/akka-docs/src/test/scala/docs/persistence/PersistenceMultiDocSpec.scala) { #default-plugins }

Java
:  @@snip [PersistenceMultiDocTest.java](/akka-docs/src/test/java/jdocs/persistence/PersistenceMultiDocTest.java) { #default-plugins }

When the persistent actor overrides the `journalPluginId` and `snapshotPluginId` methods,
the actor will be serviced by these specific persistence plugins instead of the defaults:

Scala
:  @@snip [PersistenceMultiDocSpec.scala](/akka-docs/src/test/scala/docs/persistence/PersistenceMultiDocSpec.scala) { #override-plugins }

Java
:  @@snip [PersistenceMultiDocTest.java](/akka-docs/src/test/java/jdocs/persistence/PersistenceMultiDocTest.java) { #override-plugins }

Note that `journalPluginId` and `snapshotPluginId` must refer to properly configured `reference.conf`
plugin entries with a standard `class` property as well as settings which are specific for those plugins, i.e.:

@@snip [PersistenceMultiDocSpec.scala](/akka-docs/src/test/scala/docs/persistence/PersistenceMultiDocSpec.scala) { #override-config }

## Give persistence plugin configurations at runtime

By default, a persistent actor will use the configuration loaded at `ActorSystem` creation time to create journal and snapshot store plugins.

When the persistent actor overrides the `journalPluginConfig` and `snapshotPluginConfig` methods,
the actor will use the declared `Config` objects with a fallback on the default configuration.
It allows a dynamic configuration of the journal and the snapshot store at runtime:

Scala
:  @@snip [PersistenceMultiDocSpec.scala](/akka-docs/src/test/scala/docs/persistence/PersistenceMultiDocSpec.scala) { #runtime-config }

Java
:  @@snip [PersistenceMultiDocTest.java](/akka-docs/src/test/java/jdocs/persistence/PersistenceMultiDocTest.java) { #runtime-config }

## See also

* @ref[Persistent FSM](persistence-fsm.md)
* @ref[Building a new storage backend](persistence-journals.md)
