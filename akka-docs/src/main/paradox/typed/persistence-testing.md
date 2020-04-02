# Testing

## Dependency

To use Akka Persistence and Actor TestKit, add the module to your project:

@@dependency[sbt,Maven,Gradle] {
  group1=com.typesafe.akka
  artifact1=akka-persistence-typed_$scala.binary_version$
  version1=$akka.version$
  group2=com.typesafe.akka
  artifact2=akka-actor-testkit-typed_$scala.binary_version$
  version2=$akka.version$
  scope2=test
}

## Unit testing

Unit testing of `EventSourcedBehavior` can be done with the @ref:[ActorTestKit](testing-async.md)
in the same way as other behaviors.

@ref:[Synchronous behavior testing](testing-sync.md) for `EventSourcedBehavior` is not supported yet, but
tracked in @github[issue #23712](#23712).

You need to configure a journal, and the in-memory journal is sufficient for unit testing. To enable the
in-memory journal you need to pass the following configuration to the @scala[`ScalaTestWithActorTestKit`]@java[`TestKitJunitResource`].

Scala
:  @@snip [AccountExampleDocSpec.scala](/akka-cluster-sharding-typed/src/test/scala/docs/akka/cluster/sharding/typed/AccountExampleDocSpec.scala) { #inmem-config }

Java
:  @@snip [AccountExampleDocTest.java](/akka-cluster-sharding-typed/src/test/java/jdocs/akka/cluster/sharding/typed/AccountExampleDocTest.java) { #inmem-config } 

The `test-serialization = on` configuration of the `InmemJournal` will verify that persisted events can be serialized and deserialized.

Optionally you can also configure a snapshot store. To enable the file based snapshot store you need to pass the
following configuration to the @scala[`ScalaTestWithActorTestKit`]@java[`TestKitJunitResource`].

Scala
:  @@snip [AccountExampleDocSpec.scala](/akka-cluster-sharding-typed/src/test/scala/docs/akka/cluster/sharding/typed/AccountExampleDocSpec.scala) { #snapshot-store-config }

Java
:  @@snip [AccountExampleDocTest.java](/akka-cluster-sharding-typed/src/test/java/jdocs/akka/cluster/sharding/typed/AccountExampleDocTest.java) { #snapshot-store-config }

Then you can `spawn` the `EventSourcedBehavior` and verify the outcome of sending commands to the actor using
the facilities of the @ref:[ActorTestKit](testing-async.md).

A full test for the `AccountEntity`, which is shown in the @ref:[Persistence Style Guide](persistence-style.md), may look like this:

Scala
:  @@snip [AccountExampleDocSpec.scala](/akka-cluster-sharding-typed/src/test/scala/docs/akka/cluster/sharding/typed/AccountExampleDocSpec.scala) { #test }

Java
:  @@snip [AccountExampleDocTest.java](/akka-cluster-sharding-typed/src/test/java/jdocs/akka/cluster/sharding/typed/AccountExampleDocTest.java) { #test }  

Note that each test case is using a different `PersistenceId` to not interfere with each other.

The @apidoc[akka.persistence.journal.inmem.InmemJournal$] publishes `Write` and `Delete` operations to the
`eventStream`, which makes it possible to verify that the expected events have been emitted and stored by the
`EventSourcedBehavior`. You can subscribe to to the `eventStream` with a `TestProbe` like this:

Scala
:  @@snip [AccountExampleDocSpec.scala](/akka-cluster-sharding-typed/src/test/scala/docs/akka/cluster/sharding/typed/AccountExampleDocSpec.scala) { #test-events }

Java
:  @@snip [AccountExampleDocTest.java](/akka-cluster-sharding-typed/src/test/java/jdocs/akka/cluster/sharding/typed/AccountExampleDocTest.java) { #test-events }

## Persistence TestKit

**Note!** The testkit is a new feature, api may have changes breaking source compatibility in future versions.

Persistence testkit allows to check events saved in a storage, emulate storage operations and exceptions.
To use the testkit you need to add the following dependency in your project:

@@dependency[sbt,Maven,Gradle] {
  group="com.typesafe.akka"
  artifact="akka-persistence-testkit_$scala.binary_version$"
  version="$akka.version$"
}

There are two testkit classes which have similar api:

 * @apidoc[PersistenceTestKit] class is for events
 * @apidoc[SnapshotTestKit] class is for snapshots
 
The testkit classes have two corresponding plugins which emulate the behavior of the storages: 

 * @apidoc[PersistenceTestKitPlugin] class emulates a events storage 
 * @apidoc[PersistenceTestKitSnapshotPlugin] class emulates a snapshots storage

**Note!** The corresponding plugins **must** be configured in the actor system which is used to initialize the particular testkit class:

Scala
:  @@snip [Configuration.scala](/akka-docs/src/test/scala/docs/persistence/testkit/Configuration.scala) { #testkit-typed-conf }

Java
:  @@snip [Configuration.java](/akka-docs/src/test/java/jdocs/persistence/testkit/Configuration.java) { #testkit-typed-conf }

and

Scala
:  @@snip [Configuration.scala](/akka-docs/src/test/scala/docs/persistence/testkit/Configuration.scala) { #snapshot-typed-conf }

Java
:  @@snip [Configuration.java](/akka-docs/src/test/java/jdocs/persistence/testkit/Configuration.java) { #snapshot-typed-conf }

A typical scenario is to create a persistent actor, send commands to it and check that it persists events as it is expected:

Scala
:  @@snip [TestKitExamples.scala](/akka-docs/src/test/scala/docs/persistence/testkit/TestKitExamples.scala) { #testkit-typed-usecase }

Java
:  @@snip [TestKitExamples.java](/akka-docs/src/test/java/jdocs/persistence/testkit/TestKitExamples.java) { #testkit-typed-usecase }

You can safely use persistence testkit in combination with main akka testkit.

The main methods of the api allow to (see @apidoc[PersistenceTestKit] and @apidoc[SnapshotTestKit] for more details):

 * check if the given event/snapshot object is the next persisted in the storage.
 * read a sequence of persisted events/snapshots.
 * check that no events/snapshots have been persisted in the storage.
 * throw the default exception from the storage on attempt to persist, read or delete the following event/snapshot.
 * clear the events/snapshots persisted in the storage.
 * reject the events, but not snapshots (rejections are not supported for snapshots in the original api).
 * set your own [policy](#setting-your-own-policy-for-the-storage) which emulates the work of the storage. 
Policy determines what to do when persistence needs to execute some operation on the storage (i.e. read, delete, etc.).
 * get all the events/snapshots persisted in the storage
 * put the events/snapshots in the storage to test recovery
 
#### Setting your own policy for the storage

You can implement and set your own policy for the storage to control its actions on particular operations, for example you can fail or reject events on your own conditions.
Implement the @apidoc[ProcessingPolicy[EventStorage.JournalOperation]] @scala[trait]@java[interface] for event storage
or @apidoc[ProcessingPolicy[SnapshotStorage.SnapshotOperation]] @scala[trait]@java[interface] for snapshot storage,
and set it with `withPolicy()` method.

`tryProcess()` method of the @apidoc[ProcessingPolicy] has two arguments: persistence id and the storage operation. 

Event storage has the following operations:

 * @apidoc[ReadEvents] Read the events from the storage.
 * @apidoc[WriteEvents] Write the events to the storage.
 * @apidoc[DeleteEvents] Delete the events from the storage.
 * @apidoc[ReadSeqNum] Read the highest sequence number for particular persistence id.

Snapshot storage has the following operations:

 * @apidoc[ReadSnapshot] Read the snapshot from the storage.
 * @apidoc[WriteSnapshot] Writhe the snapshot to the storage.
 * @apidoc[DeleteSnapshotsByCriteria] Delete snapshots in the storage by criteria.
 * @apidoc[DeleteSnapshotByMeta] Delete particular snapshot from the storage by its metadata.

The `tryProcess()` method must return one of the processing results:
 
 * @apidoc[ProcessingSuccess] Successful completion of the operation. All the events will be saved/read/deleted.
 * @apidoc[StorageFailure] Emulates exception from the storage.
 * @apidoc[Reject] Emulates rejection from the storage.

**Note** that snapshot storage does not have rejections. If you return `Reject` in the `tryProcess()` of the snapshot storage policy, it will have the same effect as the `StorageFailure`.

Here is an example of the policy for an event storage:

Scala
:  @@snip [TestKitExamples.scala](/akka-docs/src/test/scala/docs/persistence/testkit/TestKitExamples.scala) { #set-event-storage-policy }

Java
:  @@snip [TestKitExamples.java](/akka-docs/src/test/java/jdocs/persistence/testkit/TestKitExamples.java) { #set-event-storage-policy }

Here is an example of the policy for a snapshot storage:

Scala
:  @@snip [TestKitExamples.scala](/akka-docs/src/test/scala/docs/persistence/testkit/TestKitExamples.scala) { #set-snapshot-storage-policy }

Java
:  @@snip [TestKitExamples.java](/akka-docs/src/test/java/jdocs/persistence/testkit/TestKitExamples.java) { #set-snapshot-storage-policy } 

### Configuration of Persistence TestKit

There are several configuration properties for persistence testkit, please refer
to the @ref:[reference configuration](../general/configuration-reference.md#config-akka-persistence-testkit)

## Integration testing

The in-memory journal and file based snapshot store can be used also for integration style testing of a single
`ActorSystem`, for example when using Cluster Sharding with a single Cluster node.

For tests that involve more than one Cluster node you have to use another journal and snapshot store.
While it's possible to use the @ref:[Persistence Plugin Proxy](../persistence-plugins.md#persistence-plugin-proxy)
it's often better and more realistic to use a real database.

See [akka-samples issue #128](https://github.com/akka/akka-samples/issues/128).

### Plugin initialization

Some Persistence plugins create tables automatically, but has the limitation that it can't be done concurrently
from several ActorSystems. That can be a problem if the test creates a Cluster and all nodes tries to initialize
the plugins at the same time. To coordinate initialization you can use the `PersistenceInit` utility.

`PersistenceInit` is part of `akka-persistence-testkit` and you need to add the dependency to your project:

@@dependency[sbt,Maven,Gradle] {
  group="com.typesafe.akka"
  artifact="akka-persistence-testkit_$scala.binary_version$"
  version="$akka.version$"
}

Scala
:  @@snip [PersistenceInitSpec.scala](/akka-docs/src/test/scala/docs/persistence/testkit/PersistenceInitSpec.scala) { #imports #init }

Java
:  @@snip [PersistenceInitTest.java](/akka-docs/src/test/java/jdocs/persistence/testkit/PersistenceInitTest.java) { #imports #init }
  
