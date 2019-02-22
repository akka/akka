# Persistence - snapshotting

## Snapshots

As you model your domain using @ref:[persistent actors](persistence.md), you may notice that some actors may be
prone to accumulating extremely long event logs and experiencing long recovery times. Sometimes, the right approach
may be to split out into a set of shorter lived actors. However, when this is not an option, you can use snapshots
to reduce recovery times drastically.

Persistent actors can save snapshots of internal state every N events or when a given predicated of the state
is fulfilled.

Scala
:  @@snip [BasicPersistentActorCompileOnly.scala](/akka-persistence-typed/src/test/scala/docs/akka/persistence/typed/BasicPersistentBehaviorCompileOnly.scala) { #snapshottingEveryN }

Java
:  @@snip [BasicPersistentBehaviorTest.java](/akka-persistence-typed/src/test/java/jdocs/akka/persistence/typed/BasicPersistentBehaviorTest.java) { #snapshottingEveryN }


Scala
:  @@snip [BasicPersistentActorCompileOnly.scala](/akka-persistence-typed/src/test/scala/docs/akka/persistence/typed/BasicPersistentBehaviorCompileOnly.scala) { #snapshottingPredicate }

Java
:  @@snip [BasicPersistentBehaviorTest.java](/akka-persistence-typed/src/test/java/jdocs/akka/persistence/typed/BasicPersistentBehaviorTest.java) { #snapshottingPredicate }

When a snapshot is triggered, incoming commands are stashed until the snapshot has been saved. This means that
the state can safely be mutable although the serialization and storage of the state is performed asynchronously.
The state instance will not be updated by new events until after the snapshot has been saved.

During recovery, the persistent actor is using the latest saved snapshot to initialize the state. Thereafter the events
after the snapshot are replayed using the event handler to recover the persistent actor to its current (i.e. latest)
state.

If not specified, they default to @scala[`SnapshotSelectionCriteria.Latest`]@java[`SnapshotSelectionCriteria.latest()`]
which selects the latest (youngest) snapshot. It's possible to override the selection of which snapshot to use for
recovery like this:

Scala
:  @@snip [BasicPersistentActorCompileOnly.scala](/akka-persistence-typed/src/test/scala/docs/akka/persistence/typed/BasicPersistentBehaviorCompileOnly.scala) { #snapshotSelection }

TODO #26273 include corresponding example in Java

To disable snapshot-based recovery, applications can use @scala[`SnapshotSelectionCriteria.None`]@java[`SnapshotSelectionCriteria.none()`].
A recovery where no saved snapshot matches the specified `SnapshotSelectionCriteria` will replay all journaled
events. This can be useful if snapshot serialization format has changed in an incompatible way. It should typically
not be used when events have been deleted.

In order to use snapshots, a default snapshot-store (`akka.persistence.snapshot-store.plugin`) must be configured,
or the @scala[`PersistentActor`]@java[persistent actor] can pick a snapshot store explicitly by overriding @scala[`def snapshotPluginId: String`]@java[`String snapshotPluginId()`].

Because some use cases may not benefit from or need snapshots, it is perfectly valid not to not configure a snapshot store.
However, Akka will log a warning message when this situation is detected and then continue to operate until
an actor tries to store a snapshot, at which point the operation will fail.

## Snapshot failures

Saving snapshots can either succeed or fail – this information is reported back to the persistent actor via
the `onSnapshot` callback. Snapshot failures are, by default, logged but do not cause the actor to stop or
restart.

If there is a problem with recovering the state of the actor from the journal when the actor is
started, `onRecoveryFailure` is called (logging the error by default), and the actor will be stopped.
Note that failure to load snapshot is also treated like this, but you can disable loading of snapshots
if you for example know that serialization format has changed in an incompatible way.
