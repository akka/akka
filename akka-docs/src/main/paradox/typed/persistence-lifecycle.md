# Persistence - lifecycle

# Four behaviors of EventSourcedBehavior
 
1. RequestingRecoveryPermit: Requests a permit to start replaying this actor. 
This avoids hammering the journal with too many concurrently replaying actors.
 
2. ReplayingSnapshot: In this behavior the recovery process is initiated. 
We try to obtain a snapshot from the configured snapshot store, and if it exists, we use it instead of the initial `emptyState`.
If an event replay fails, due to re-processing or the snapshot recovery timeout has been met,
the error is logged and the actor is stopped. 
 
3. ReplayingEvents: In this behavior event replay finally begins, starting from the last applied sequence number
(i.e. the one up-until-which the snapshot recovery has brought us). Once recovery is completed successfully, the state becomes running.
  
4. Running (or, 'Final'): In this state recovery has completed successfully, stashed messages are flushed
and control is given to the handlers to drive the behavior from there.
Handling incoming commands continues, as well as persisting new events as dictated by the handlers.
This behavior operates in two phases (also behaviors):
    - HandlingCommands - where the command handler is invoked for incoming commands
    - PersistingEvents - where incoming commands are stashed until persistence completes
 
## Recovery

It is strongly discouraged to perform side effects in `applyEvent`,
Side effects should be performed once recovery has completed as a reaction to the `RecoveryCompleted` signal @scala[`receiveSignal` handler] @java[by overriding `receiveSignal`]

Scala
:  @@snip [BasicPersistentBehaviorCompileOnly.scala](/akka-persistence-typed/src/test/scala/docs/akka/persistence/typed/BasicPersistentBehaviorCompileOnly.scala) { #recovery }

Java
:  @@snip [BasicPersistentBehaviorTest.java](/akka-persistence-typed/src/test/java/jdocs/akka/persistence/typed/BasicPersistentBehaviorTest.java) { #recovery }

The `RecoveryCompleted` contains the current `State`.

@ref[Snapshots)[persistence-snapshot.md] can be used for optimizing recovery times.

## Retention - snapshots and events

Retention of snapshots and events are controlled by a few factors. Deletes to free up space is currently available.

@@@ note

The retention API itself [is under current development, e.g. #26545](https://github.com/akka/akka/issues/26545)
 
@@@ note
  
### Snapshot deletion

To free up space, an event sourced actor can automatically delete older snapshots 
if there are saved snapshots which match the `SnapshotSelectionCriteria`. 
Deletion of snapshots is currently based on a configured or default `RetentionCriteria` 
from `withRetention`combined with either the `snapshotWhen` or `snapshotEvery` methods. 
If snapshots are enabled, the deletion sliding window leverages the `RetentionCriteria` to construct
a `SnapshotSelectionCriteria` based on the latest sequence number. 
 
@@@ note

[This API will be simplified soon](https://github.com/akka/akka/issues/26544).

@@@ note
  
Scala
:  @@snip [BasicPersistentBehaviorCompileOnly.scala](/akka-persistence-typed/src/test/scala/docs/akka/persistence/typed/BasicPersistentBehaviorCompileOnly.scala) { #snapshotDeletes }

Java
:  @@snip [BasicPersistentBehaviorTest.java](/akka-persistence-typed/src/test/java/jdocs/akka/persistence/typed/BasicPersistentBehaviorTest.java) { #snapshotDeletes }

On async deletion, either a `SnapshotCompleted` or `SnapshotFailed` is returned. Successful completion is logged by the system at log level `debug`, failures at log level `error`.
You can leverage `EventSourcedSignal` to react to outcomes with `EventSourcedBehavior.receiveSignal`.

Scala
:  @@snip [BasicPersistentBehaviorCompileOnly.scala](/akka-persistence-typed/src/test/scala/docs/akka/persistence/typed/BasicPersistentBehaviorCompileOnly.scala) { #fullDeletesSampleWithSignals }

Java
:  @@snip [BasicPersistentBehaviorTest.java](/akka-persistence-typed/src/test/java/jdocs/akka/persistence/typed/BasicPersistentBehaviorTest.java) { #snapshotDeletesSifullDeletesSampleWithSignalsgnal }

## Event deletion
If snapshot-based recovery is enabled, you can elect to have the system automatically delete older events by enabling `RetentionCriteria.deleteEventsOnSnapshot`, which is disabled by default. 
Additionally you can leverage `EventSourcedSignal` to react to outcomes with `EventSourcedBehavior.receiveSignal`.
   
Scala
:  @@snip [BasicPersistentBehaviorCompileOnly.scala](/akka-persistence-typed/src/test/scala/docs/akka/persistence/typed/BasicPersistentBehaviorCompileOnly.scala) { #snapshotAndEventDeletes }

Java
:  @@snip [BasicPersistentBehaviorTest.java](/akka-persistence-typed/src/test/java/jdocs/akka/persistence/typed/BasicPersistentBehaviorTest.java) { #snapshotAndEventDeletes }
 
On `SaveSnapshotSuccess`, old events would be deleted based on `RetentionCriteria` prior to old snapshots being deleted. On async deletion, either `DeleteEventsCompleted` or `DeleteEventsFailed` is returned. Successful completion is logged by the 
system at log level `debug`, failures at log level `error`. You can leverage `EventSourcedSignal` to react to outcomes with `EventSourcedBehavior.receiveSignal`.

Message deletion does not affect the highest sequence number of the journal, even if all messages were deleted from it after a delete occurs.
