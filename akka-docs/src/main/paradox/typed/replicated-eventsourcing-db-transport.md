# Replicated Event Sourcing replication via direct access to replica databases

@@@ note

Since Akka 2.8.0 a gRPC based transport is the recommended way to set up the replication of events between the replicas.

@@@

It is possible to consume events with a direct connection to the database backing each replica. 
Such a setup is generally harder to set up and secure, and is less feasible unless the replication is 
over a private network.

To enable an entity for Replicated Event Sourcing
@java[let it extend `ReplicatedEventSourcedBehavior` instead of `EventSourcedBehavior` and] use the factory methods on @scala[`akka.persistence.typed.scaladsl.ReplicatedEventSourcing`]@java[`akka.persistence.typed.javadsl.ReplicatedEventSourcing`]. 

All replicas need to be known up front:

Scala
:  @@snip [ReplicatedEventSourcingCompileOnlySpec.scala](/akka-persistence-typed-tests/src/test/scala/docs/akka/persistence/typed/ReplicatedEventSourcingCompileOnlySpec.scala) { #replicas }

Java
:  @@snip [MyReplicatedBehavior.java](/akka-persistence-typed-tests/src/test/java/jdocs/akka/persistence/typed/MyReplicatedBehavior.java) { #replicas }


Then to enable replication create the event sourced behavior with the factory method:

Scala
:  @@snip [ReplicatedEventSourcingCompileOnlySpec.scala](/akka-persistence-typed-tests/src/test/scala/docs/akka/persistence/typed/ReplicatedEventSourcingCompileOnlySpec.scala) { #factory }

Java
:  @@snip [MyReplicatedBehavior.java](/akka-persistence-typed-tests/src/test/java/jdocs/akka/persistence/typed/MyReplicatedBehavior.java) { #factory }

The factory takes in:

* `entityId`: this will be used as part of the underlying persistenceId
* `replicaId`: Which replica this instance is
* `allReplicasAndQueryPlugins`: All Replicas and the query plugin used to read their events
* A factory function to create an instance of the @scala[`EventSourcedBehavior`]@java[`ReplicatedEventSourcedBehavior`] 

In this scenario each replica reads from each other's database effectively providing cross region replication for any database that has an Akka Persistence plugin. Alternatively if all the replicas use the same journal, e.g. for testing or if it is a distributed database such as Cassandra, the `withSharedJournal` factory can be used. 

Scala
:  @@snip [ReplicatedEventSourcingCompileOnlySpec.scala](/akka-persistence-typed-tests/src/test/scala/docs/akka/persistence/typed/ReplicatedEventSourcingCompileOnlySpec.scala) { #factory-shared}

Java
:  @@snip [MyReplicatedBehavior.java](/akka-persistence-typed-tests/src/test/java/jdocs/akka/persistence/typed/MyReplicatedBehavior.java) { #factory-shared }


@@@ div { .group-scala }

The function passed to both factory methods return an `EventSourcedBehavior` and provide access to the @apidoc[ReplicationContext] that has the following methods:

* `entityId`
* `replicaId`
* `allReplicas`
* `persistenceId` - to provide to the `EventSourcedBehavior` factory. This **must be used**.

As well as methods that **can only be** used in the event handler. The values these methods return relate to the event that is being processed.

@@@

@@@ div { .group-java }

The function passed to both factory methods is invoked with a special @apidoc[ReplicationContext] that needs to be passed to the
concrete `ReplicatedEventSourcedBehavior` and on to the super constructor.

The context gives access to: 

* `entityId`
* `replicaId`
* `allReplicas`
* `persistenceId`

As well as methods that **can only be** used in the event handler, accessed through `getReplicationContext`. The values these methods return relate to the event that is being processed.

@@@

* `origin`: The ReplicaId that originally created the event
* `concurrent`: Whether the event was concurrent with another event as in the second diagram above
* `recoveryRunning`: Whether a recovery is running. Can be used to send commands back to self for side effects that should only happen once.
* `currentTimeMillis`: similar to `System.currentTimeMillis` but guaranteed never to go backwards

The factory returns a `Behavior` that can be spawned like any other behavior.

## Sharded Replicated Event Sourced entities

There are three ways to integrate replicated event sourced entities with sharding:

* Ensure that each replica has a unique entity id by using the replica id as part of the entity id
* Use @ref[multi datacenter](cluster-dc.md)  to run a full copy of sharding per replica
* Use roles to run a full copy of sharding per replica


To simplify all three cases the @apidoc[ReplicatedShardingExtension] is available from the
`akka-cluster-sharding-typed` module.

Scala
:  @@snip [ReplicatedShardingSpec.scala](/akka-cluster-sharding-typed/src/test/scala/docs/akka/cluster/sharding/typed/ReplicatedShardingCompileOnlySpec.scala) { #bootstrap }

Java
:  @@snip [ReplicatedShardingTest.java](/akka-cluster-sharding-typed/src/test/java/jdocs/akka/cluster/sharding/typed/ReplicatedShardingCompileOnlySpec.java) { #bootstrap }

This will run an instance of sharding and per replica and each entity id contains the replica id and the type name.
Replicas could be on the same node if they end up in the same shard or if the shards get allocated to the same node.

To prevent this roles can be used. You could for instance add a cluster role per availability zone / rack and have a replica per rack.

Scala
:  @@snip [ReplicatedShardingSpec.scala](/akka-cluster-sharding-typed/src/test/scala/docs/akka/cluster/sharding/typed/ReplicatedShardingCompileOnlySpec.scala) { #bootstrap-role }

Java
:  @@snip [ReplicatedShardingTest.java](/akka-cluster-sharding-typed/src/test/java/jdocs/akka/cluster/sharding/typed/ReplicatedShardingCompileOnlySpec.java) { #bootstrap-role }

Lastly if your Akka Cluster is setup across DCs you can run a replica per DC.

Scala
:  @@snip [ReplicatedShardingSpec.scala](/akka-cluster-sharding-typed/src/test/scala/docs/akka/cluster/sharding/typed/ReplicatedShardingCompileOnlySpec.scala) { #bootstrap-dc }

Java
:  @@snip [ReplicatedShardingTest.java](/akka-cluster-sharding-typed/src/test/java/jdocs/akka/cluster/sharding/typed/ReplicatedShardingCompileOnlySpec.java) { #bootstrap-dc }

Regardless of which replication strategy you use sending messages to the replicated entities is the same.

`init` returns an @apidoc[ReplicatedSharding] instance which gives access to @apidoc[EntityRef]s for each of the replicas for arbitrary routing logic:

Scala
:  @@snip [ReplicatedShardingSpec.scala](/akka-cluster-sharding-typed/src/test/scala/docs/akka/cluster/sharding/typed/ReplicatedShardingCompileOnlySpec.scala) { #sending-messages }

Java
:  @@snip [ReplicatedShardingTest.java](/akka-cluster-sharding-typed/src/test/java/jdocs/akka/cluster/sharding/typed/ReplicatedShardingCompileOnlySpec.java) { #sending-messages }

More advanced routing among the replicas is currently left as an exercise for the reader (or may be covered in a future release [#29281](https://github.com/akka/akka/issues/29281), [#29319](https://github.com/akka/akka/issues/29319)).

## Tagging events and running projections

Just like for regular `EventSourcedBehavior`s it is possible to tag events along with persisting them. 
This is useful for later retrival of events for a given tag. The same @ref[API for tagging provided for EventSourcedBehavior](persistence.md#tagging) can 
be used for replicated event sourced behaviors as well.
Tagging is useful in practice to build queries that lead to other data representations or aggregations of these event 
streams that can more directly serve user queries – known as building the “read side” in @ref[CQRS](cqrs.md) based applications.

Creating read side projections is possible through @extref[Akka Projections](akka-projection:)
or through direct usage of the @ref[events by tag queries](../persistence-query.md#eventsbytag-and-currenteventsbytag).  

The tagging is invoked in each replicas, which requires some special care in using tags, or else the same event will be
tagged one time for each replica and show up in the event by tag stream one time for each replica. In addition to this
the tags will be written in the respective journal of the replicas, which means that unless they all share a single journal
the tag streams will be local to the replica even if the same tag is used on multiple replicas.

One strategy for dealing with this is to include the replica id in the tag name, this means there will be a tagged stream of events
per replica that contains all replicated events, but since the events can arrive in different order, they can also come in different
order per replica tag.

Another strategy would be to tag only the events that are local to the replica and not events that are replicated. Either
using a tag that will be the same for all replicas, leading to a single stream of tagged events where the events from each 
replica is present only once, or with a tag including the replica id meaning that there will be a stream of tagged events
with the events accepted locally for each replica.

Determining the replica id of the replicated actor itself and the origin replica id of an event is possible through the
@apidoc[ReplicationContext] when the tagger callback is invoked like this:

Scala
:  @@snip [ReplicatedEventSourcingTaggingSpec.scala](/akka-persistence-typed-tests/src/test/scala/akka/persistence/typed/ReplicatedEventSourcingTaggingSpec.scala) { #tagging }

Java
:  @@snip [ReplicatedStringSet.java](/akka-persistence-typed-tests/src/test/java/jdocs/akka/persistence/typed/ReplicatedStringSet.java) { #tagging }

In this sample we are using a shared journal, and single tag but only tagging local events to it and therefore ending up
with a single stream of tagged events from all replicas without duplicates. 

## Direct Replication of Events

Each replica will read the events from all the other copies from the database. When used with Cluster Sharding, and to make the sharing
of events with other replicas more efficient, each replica publishes the events across the Akka cluster directly to other replicas.
The delivery of events across the cluster is not guaranteed so the query to the journal is still needed but can be configured to 
poll the database less often since most events will arrive at the replicas through the cluster.

The direct replication of events feature is enabled by default when using Cluster Sharding. 
To disable this feature you first need to:
 
1. disable event publishing @scala[on the `EventSourcedBehavior` with `withEventPublishing(false)`]@java[overriding `withEventPublishing` from `ReplicatedEventSourcedBehavior` to return `false`] , and then 
2. disable direct replication through `withDirectReplication(false)` on @apidoc[ReplicatedEntityProvider] 

The "event publishing" feature publishes each event to the local system event bus as a side effect after it has been written.

## Examples

More examples can be found in @ref[Replicated Event Sourcing Examples](./replicated-eventsourcing-examples.md)

## Journal Support

For a journal plugin to support replication it needs to store and read metadata for each event if it is defined in the @apiref[PersistentRepr]
 `metadata` field. To attach the metadata after writing it, `PersistentRepr.withMetadata` is used. The @apidoc[JournalSpec] in the Persistence TCK provides 
 a capability flag `supportsMetadata` to toggle verification that metadata is handled correctly.
 
For a snapshot plugin to support replication it needs to store and read metadata for the snapshot if it is defined in the @apiref[akka.persistence.SnapshotMetadata] `metadata` field. 
To attach the metadata when reading the snapshot the `akka.persistence.SnapshotMetadata.apply` factory overload taking a `metadata` parameter is used.
The @apidoc[SnapshotStoreSpec] in the Persistence TCK provides a capability flag `supportsMetadata` to toggle verification that metadata is handled correctly.

The following plugins support Replicated Event Sourcing:

* [Akka Persistence Cassandra](https://doc.akka.io/docs/akka-persistence-cassandra/current/index.html) versions 1.0.3+
* [Akka Persistence R2DBC](https://doc.akka.io/docs/akka-persistence-r2dbc/current/) versions 1.0.0+
* [Akka Persistence JDBC](https://doc.akka.io/docs/akka-persistence-jdbc/current) versions 5.0.0+
