# Replicated Event Sourcing

@@@ warning

This module is marked as @ref:[may change](../common/may-change.md) because it is a new feature that
needs feedback from real usage before finalizing the API. This means that API or semantics can change without
warning or deprecation period. It is also not recommended to use this module in production just yet.

@@@

@ref[Event sourcing](./persistence.md) with `EventSourcedBehavior`s is based on the single writer principle, which means that there can only be one active instance of a `EventSourcedBehavior` with a given `persistenceId`. Otherwise, multiple instances would store interleaving events based on different states, and when these events would later be replayed it would not be possible to reconstruct the correct state.

This restriction means that in the event of network partitions, and for a short time during rolling re-deploys, `EventSourcedBehaviors`s are unavailable.


Replicated Event Sourcing enables running multiple replicas of each entity. 
There is automatic replication of every event persisted to all replicas.

For instance, a replica can be run per:

* Data Center 
* Availability zone or rack

The motivations are:

* Redundancy to tolerate failures in one location and still be operational
* Serve requests from a location near the user to provide better responsiveness
* Balance the load over many servers

However, the event handler must be able to **handle concurrent events** as when replication is enabled
there is no longer the single writer principle as there is with a normal `EventSourcedBehavior`.

The state of a replicated `EventSourcedBehavior` is **eventually consistent**. Event replication may be delayed
due to network partitions and outages and the event handler and those reading the state must be designed to handle this.

To be able to use Replicated Event Sourcing the journal and snapshot store used is required to have specific support for the metadata that the replication needs (see @ref[Journal Support](#journal-support))

## Relaxing the single writer principle for availability

Taking the example of using Replicated Event Sourcing to run a replica per data center.

When there is no network partitions and no concurrent writes the events stored by a `EventSourcedBehavior` at one replica can be replicated and consumed by another (corresponding) replica in another data center without any concerns. Such replicated events can simply be applied to the local state.

![images/replicated-events1.png](images/replicated-events1.png)

The interesting part begins when there are concurrent writes by `EventSourcedBehavior` replicas. That is more likely to happen when there is a network partition, but it can also happen when there are no network issues. They simply write at the "same time" before the events from the other side have been replicated and consumed.

![images/replicated-events2.png](images/replicated-events2.png)

The event handler logic for applying events to the state of the entity must be aware of that such concurrent updates can occur, and it must be modeled to handle such conflicts. This means that it should typically have the same characteristics as a Conflict Free Replicated Data Type (CRDT). With a CRDT there are by definition no conflicts, the events can always be applied. The library provides some general purpose CRDTs, but the logic of how to apply events can also be defined by an application specific function.

For example, sometimes it's enough to use application specific timestamps to decide which update should win.

To assist in implementing the event handler active-active detects these conflicts.

## API

@scala[The same API as regular `EventSourcedBehavior`s]@java[A very similar API to the regular `EventSourcedBehavior`] is used to define the logic. 

To enable an entity for active-active
replication @java[let it extend `ReplicatedEventSourcedBehavior` instead of `EventSourcedBehavior` and] use the factory methods on @scala[`akka.persistence.typed.scaladsl.ReplicatedEventSourcing`]@java[`akka.persistence.typed.javadsl.ReplicatedEventSourcing`]. 

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

* EntityID: this will be used as part of the underlying persistenceId
* Replica: Which replica this instance is
* All Replicas and the query plugin used to read their events
* A factory function to create an instance of the @scala[`EventSourcedBehavior`]@java[`ReplicatedEventSourcedBehavior`] 

In this scenario each replica reads from each other's database effectively providing cross region replication for any database that has an Akka Persistence plugin. Alternatively if all the replicas use the same journal, e.g. for testing or if it is a distributed database such as Cassandra, the `withSharedJournal` factory can be used. 

Scala
:  @@snip [ReplicatedEventSourcingCompileOnlySpec.scala](/akka-persistence-typed-tests/src/test/scala/docs/akka/persistence/typed/ReplicatedEventSourcingCompileOnlySpec.scala) { #factory-shared}

Java
:  @@snip [MyReplicatedBehavior.java](/akka-persistence-typed-tests/src/test/java/jdocs/akka/persistence/typed/MyReplicatedBehavior.java) { #factory-shared }


@@@ div { .group-scala }

The function passed to both factory methods return an `EventSourcedBehavior` and provide access to the @apidoc[ReplicationContext] that has the following methods:

* entityId 
* replicaId
* allReplicas
* persistenceId - to provide to the `EventSourcedBehavior` factory. This **must be used**.

As well as methods that **can only be** used in the event handler. The values these methods return relate to the event that is being processed.

@@@

@@@ div { .group-java }

The function passed to both factory methods is invoked with a special @apidoc[ReplicationContext] that needs to be passed to the
concrete `ReplicatedEventSourcedBehavior` and on to the super constructor.

The context gives access to: 

* entityId 
* replicaId
* allReplicas
* persistenceId

As well as methods that **can only be** used in the event handler, accessed through `getReplicationContext`. The values these methods return relate to the event that is being processed.

@@@

* origin: The ReplicaId that originally created the event
* concurrent: Whether the event was concurrent with another event as in the second diagram above
* recoveryRunning: Whether a recovery is running. Can be used to send commands back to self for side effects that should only happen once.
* currentTimeMillis: similar to `System.currentTimeMillis` but guaranteed never to go backwards

The factory returns a `Behavior` that can be spawned like any other behavior.

## Resolving conflicting updates

### Conflict free replicated data types

TODO example once CRDTs are in

### Last writer wins

Sometimes it is enough to use timestamps to decide which update should win. Such approach relies on synchronized clocks, and clocks of different machines will always be slightly out of sync. Timestamps should therefore only be used when the choice of value is not important for concurrent updates occurring within the clock skew.
 
 In general, last writer wins means that the event is used if the timestamp of the event is later (higher) than the timestamp of previous local update, otherwise the event is discarded. There is no built-in support for last writer wins, because it must often be combined with more application specific aspects.
 
![images/lww.png](images/lww.png)

There is a small utility class @apidoc[LwwTime] that can be useful for implementing last writer wins semantics.
It contains a timestamp representing current time when the event was persisted and an identifier of the
replica that persisted it. When comparing two @apidoc[LwwTime] the greatest timestamp wins. The replica
identifier is used if the two timestamps are equal, and then the one from the data center sorted first in
alphanumeric order wins.

The nature of last writer wins means that if you only have one timestamp for the state the events must represent an
update of the full state. Otherwise, there is a risk that the state in different data centers will be different and
not eventually converge.

An example of that would be an entity representing a blog post and the fields `author` and `title` could be updated
separately with events @scala[`AuthorChanged(newAuthor: String)`]@java[`new AuthorChanged(newAuthor)`] and @scala[`TitleChanged(newTitle: String)`]@java[`new TitleChanged(newTitle)`].

Let's say the blog post is created and the initial state of `title=Akka, author=unknown` is in sync in both replicas `DC-A` and `DC-B.

In `DC-A` author is changed to "Bob" at time `100`. Before that event has been replicated over to `DC-B` the
title is updated to "Akka News" at time `101` in `DC-B`. When the events have been replicated the result will be:

`DC-A`: The title update is later so the event is used and new state is `title=Akka News, author=Bob`

`DC-B`: The author update is earlier so the event is discarded and state is `title=Akka News, author=unknown`

The problem here is that the partial update of the state is not applied on both sides, so the states have diverged and will not become the same.

To solve this with last writer wins the events must carry the full state, such as @scala[`AuthorChanged(newContent: PostContent)`]@java[`new AuthorChanged(newContent)`] and @scala[`TitleChanged(newContent: PostContent)`]@java[`new TitleChanged(newContent)`]. Then the result would eventually be `title=Akka News, author=unknown` on both sides.
The author update is lost but that is because the changes were performed concurrently. More important is that the state
is eventually consistent.

Including the full state in each event is often not desired. An event typically represent a change, a delta. Then one can use several timestamps, one for each set of fields that can be updated together. In the above example one could use one timestamp for the title and another for the author. Then the events could represent changes to parts of the full state, such as @scala[`AuthorChanged(newAuthor: String)`]@java[`new AuthorChanged(newAuthor)`] and @scala[`TitleChanged(newTitle: String)`]@java[`new TitleChanged(newTitle)`].

## Side effects

In most cases it is recommended to do side effects as @ref[described for `EventSourcedBehavior`s](./persistence.md#effects-and-side-effects).

Side effects from the event handler are generally discouraged because the event handlers are also used during replay and when consuming replicated events and that would 
result in undesired re-execution of the side effects.

Uses cases for doing side effects in the event handler:
* Doing a side effect only in a single replica
* Doing a side effect once all replicas have seen an event
* A side effect for a replicated event
* A side effect when a conflict has occured

There is no built in support for knowing an event has been replicated to all replicas but it can be modelled in your state. 
For some use cases you may need to trigger side effects after consuming replicated events. For example when an auction has been closed in 
all data centers and all bids have been replicated. 

The @api[ReplicationContext] contains the current replica, the origin replica for the event processes, and if a recovery is running. These can be used to 
implement side effects that take place once events are fully replicated. If the side effect should happen only once then a particular replica can be
designated to do it. The @ref[Auction example](./replicated-eventsourcing-examples.md#auction) uses these techniques.


## How it works

You don’t have to read this section to be able to use the feature, but to use the abstraction efficiently and for the right type of use cases it can be good to understand how it’s implemented. For example, it should give you the right expectations of the overhead that the solution introduces compared to using just `EventSourcedBehavior`s.

### Causal deliver order

Causal delivery order means that events persisted in one data center are read in the same order in other data centers. The order of concurrent events is undefined, which should be no problem
when using [CRDT's](#conflict-free-replicated-data-types)
and otherwise will be detected via the `ReplicationContext` concurrent method.

For example:

```
DC-1: write e1
DC-2: read e1, write e2
DC-1: read e2, write e3
```

In the above example the causality is `e1 -> e2 -> e3`. Also in a third data center DC-3 these events will be read in the same order e1, e2, e3.

Another example with concurrent events:

```
DC1: write e1
DC2: read e1, write e2
DC1: write e3 (e2 and e3 are concurrent)
DC1: read e2
DC2: read e3
```

e2 and e3 are concurrent, i.e. they don't have a causal relation: DC1 sees them in the order "e1, e3, e2", while DC2 sees them as "e1, e2, e3".

A third data center may also see the events as either "e1, e3, e2" or as "e1, e2, e3".

### Concurrent updates

Replicated Event Sourcing automatically tracks causality between events from different replicas using [version vectors](https://en.wikipedia.org/wiki/Version_vector).

![images/causality.png](images/causality.png)

Each replica "owns" a slot in the version vector and increases its counter when an event is persisted. The version vector is stored with the event, and when a replicated event is consumed the version vector of the event is merged with the local version vector.

When comparing two version vectors `v1` and `v2`: 

* `v1` is SAME as `v2` iff for all i v1(i) == v2(i)
* `v1`is BEFORE `v2` iff for all i v1(i) <= v2(i) and there exist a j such that v1(j) < v2(j)
* `v1`is AFTER `v2` iff for all i v1(i) >= v2(i) and there exist a j such that v1(j) > v2(j)
* `v1`is CONCURRENT with `v2` otherwise


## Sharded Replicated Event Sourced entities

To simplify what probably are the most common use cases for how you will want to distribute the replicated actors there is a minimal API for running multiple instances of @ref[Akka Cluster Sharding](cluster-sharding.md), 
each instance holding the entities for a single replica.

The distribution of the replicas can be controlled either through cluster roles or using the @ref[multi datacenter](cluster-dc.md) support in Akka Cluster. 

The API consists of bootstrapping logic for starting the sharding instances through @apidoc[ReplicatedShardingExtension] available from the
`akka-cluster-sharding-typed` module.

Scala
:  @@snip [ReplicatedShardingSpec.scala](/akka-cluster-sharding-typed/src/test/scala/akka/cluster/sharding/typed/ReplicatedShardingSpec.scala) { #bootstrap }

Java
:  @@snip [ReplicatedShardingTest.java](/akka-cluster-sharding-typed/src/test/java/akka/cluster/sharding/typed/ReplicatedShardingTest.java) { #bootstrap }

`init` returns an @apidoc[ReplicatedSharding] instance which gives access to @apidoc[EntityRef]s for each of the replicas for arbitrary routing logic:

Scala
:  @@snip [ReplicatedShardingSpec.scala](/akka-cluster-sharding-typed/src/test/scala/akka/cluster/sharding/typed/ReplicatedShardingSpec.scala) { #all-entity-refs }

Java
:  @@snip [ReplicatedShardingTest.java](/akka-cluster-sharding-typed/src/test/java/akka/cluster/sharding/typed/ReplicatedShardingTest.java) { #all-entity-refs }

More advanced routing among the replicas is currently left as an exercise for the reader (or may be covered in a future release [#29281](https://github.com/akka/akka/issues/29281), [#29319](https://github.com/akka/akka/issues/29319)).

## Tagging events and running projections

Just like for regular `EventSourcedBehavior`s it is possible to tag events along with persisting them. 
This is useful for later retrival of events for a given tag. The same @ref[API for tagging provided for EventSourcedBehavior](persistence.md#tagging) can 
be used for replicated event sourced behaviors as well.
Tagging is useful in practice to build queries that lead to other data representations or aggregations of the these event 
streams that can more directly serve user queries – known as building the “read side” in @ref[CQRS](cqrs.md) based applications.

Creating read side projections is possible through [Akka Projection](https://doc.akka.io/docs/akka-projection/current/)
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

Normally an event has to be written in the journal and then picked up by the trailing read journal in the other replicas. 
As an optimization the replicated events can be published across the Akka cluster to the replicas. The read side
query is still needed as delivery is not guaranteed, but can be configured to poll the database less often since most
events will arrive at the replicas through the cluster.

To enable this feature you first need to enable event publishing on the @scala[`EventSourcedBehavior`]@java[`ReplicatedEventSourcedBehavior`] with `withEventPublishing` 
and then enable direct replication through `withDirectReplication()` on @apidoc[ReplicatedShardingSettings] (if not using
 replicated sharding the replication can be run standalone by starting the @apidoc[ShardingDirectReplication] actor).

The "event publishing" feature publishes each event to the local system event bus as a side effect after it has been written, 
the @apidoc[ShardingDirectReplication] actor subscribes to these events and forwards them to the replicas allowing them
to fast forward the stream of events for the origin replica. (With additional potential future support in journals for fast forwarding [#29311](https://github.com/akka/akka/issues/29311)). 

## Hot Standby

If all writes occur to one replica the other replicas are not started there might be many replicated events to catch up with when they are later started. Therefore it can be good to activate all replicas when there is some activity. 

This can be achieved automatically when `ReplicatedSharding` is used and direct replication of events is enabled as described in @ref[Direct Replication of Events](#direct-replication-of-events). When each written event is forwarded to the other replicas it will trigger them to start if they are not already started.

## Journal Support

For a journal plugin to support replication it needs to store and read metadata for each event if it is defined in the @apiref[PersistentRepr]
 `metadata` field. To attach the metadata after writing it, `PersistentRepr.withMetadata` is used. The @apidoc[JournalSpec] in the Persistence TCK provides 
 a capability flag `supportsMetadata` to toggle verification that metadata is handled correctly.
 
For a snapshot plugin to support replication it needs to store and read metadata for the snapshot if it is defined in the @apiref[akka.persistence.SnapshotMetadata] `metadata` field. 
To attach the metadata when reading the snapshot the `akka.persistence.SnapshotMetadata.apply` factory overload taking a `metadata` parameter is used.
The @apidoc[SnapshotStoreSpec] in the Persistence TCK provides a capability flag `supportsMetadata` to toggle verification that metadata is handled correctly.
