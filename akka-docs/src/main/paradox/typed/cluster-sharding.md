---
project.description: Shard a clustered compute process across the network with locationally transparent message routing using Akka Cluster Sharding.
---
# Cluster Sharding

You are viewing the documentation for the new actor APIs, to view the Akka Classic documentation, see @ref:[Classic Cluster Sharding](../cluster-sharding.md)

## Module info

To use Akka Cluster Sharding, you must add the following dependency in your project:

@@dependency[sbt,Maven,Gradle] {
  symbol1=AkkaVersion
  value1="$akka.version$"
  group=com.typesafe.akka
  artifact=akka-cluster-sharding-typed_$scala.binary.version$
  version=AkkaVersion
}

@@project-info{ projectId="akka-cluster-sharding-typed" }

## Introduction

Cluster sharding is useful when you need to distribute actors across several nodes in the cluster and want to
be able to interact with them using their logical identifier, but without having to care about
their physical location in the cluster, which might also change over time.

It could for example be actors representing Aggregate Roots in Domain-Driven Design terminology.
Here we call these actors "entities". These actors typically have persistent (durable) state,
but this feature is not limited to actors with persistent state.

The [Introduction to Akka Cluster Sharding video](https://akka.io/blog/news/2019/12/16/akka-cluster-sharding-intro-video)
is a good starting point for learning Cluster Sharding.

Cluster sharding is typically used when you have many stateful actors that together consume
more resources (e.g. memory) than fit on one machine. If you only have a few stateful actors
it might be easier to run them on a @ref:[Cluster Singleton](cluster-singleton.md) node. 

In this context sharding means that actors with an identifier, so called entities,
can be automatically distributed across multiple nodes in the cluster. Each entity
actor runs only at one place, and messages can be sent to the entity without requiring
the sender to know the location of the destination actor. This is achieved by sending
the messages via a `ShardRegion` actor provided by this extension, which knows how
to route the message with the entity id to the final destination.

Cluster sharding will not be active on members with status @ref:[WeaklyUp](cluster-membership.md#weaklyup-members)
if that feature is enabled.

@@@ warning

Make sure to not use a Cluster downing strategy that may split the cluster into several separate clusters in
case of network problems or system overload (long GC pauses), since that will result in *multiple shards and entities*
being started, one in each separate cluster!
See @ref:[Downing](cluster.md#downing).

@@@

## Basic example

Sharding is accessed via the `ClusterSharding` extension

Scala
:  @@snip [ShardingCompileOnlySpec.scala](/akka-cluster-sharding-typed/src/test/scala/docs/akka/cluster/sharding/typed/ShardingCompileOnlySpec.scala) { #sharding-extension }

Java
:  @@snip [ShardingCompileOnlyTest.java](/akka-cluster-sharding-typed/src/test/java/jdocs/akka/cluster/sharding/typed/ShardingCompileOnlyTest.java) { #import #sharding-extension }

It is common for sharding to be used with persistence however any `Behavior` can be used with sharding e.g. a basic counter:

Scala
:  @@snip [ShardingCompileOnlySpec.scala](/akka-cluster-sharding-typed/src/test/scala/docs/akka/cluster/sharding/typed/ShardingCompileOnlySpec.scala) { #counter }

Java
:  @@snip [ShardingCompileOnlyTest.java](/akka-cluster-sharding-typed/src/test/java/jdocs/akka/cluster/sharding/typed/ShardingCompileOnlyTest.java) { #counter }

Each Entity type has a key that is then used to retrieve an EntityRef for a given entity identifier. 
Note in the sample's @scala[`Counter.apply`]@java[`Counter.create`] function that the `entityId` parameter is not
called, it is included to demonstrate how one can pass it to an entity. Another way to do this is by sending the `entityId` as part of the message if needed.

Scala
:  @@snip [ShardingCompileOnlySpec.scala](/akka-cluster-sharding-typed/src/test/scala/docs/akka/cluster/sharding/typed/ShardingCompileOnlySpec.scala) { #init }

Java
:  @@snip [ShardingCompileOnlyTest.java](/akka-cluster-sharding-typed/src/test/java/jdocs/akka/cluster/sharding/typed/ShardingCompileOnlyTest.java) { #init }

Messages to a specific entity are then sent via an `EntityRef`.
It is also possible to wrap methods in a `ShardingEnvelope` or define extractor functions and send messages directly to the shard region.

Scala
:  @@snip [ShardingCompileOnlySpec.scala](/akka-cluster-sharding-typed/src/test/scala/docs/akka/cluster/sharding/typed/ShardingCompileOnlySpec.scala) { #send }

Java
:  @@snip [ShardingCompileOnlyTest.java](/akka-cluster-sharding-typed/src/test/java/jdocs/akka/cluster/sharding/typed/ShardingCompileOnlyTest.java) { #send }

Cluster sharding `init` should be called on every node for each entity type. Which nodes entity actors are created on
can be controlled with @ref:[roles](cluster.md#node-roles). `init` will create a `ShardRegion` or a proxy depending on whether the node's role matches
the entity's role. 

Specifying the role:

Scala
:  @@snip [ShardingCompileOnlySpec.scala](/akka-cluster-sharding-typed/src/test/scala/docs/akka/cluster/sharding/typed/ShardingCompileOnlySpec.scala) { #roles }

Java
:  @@snip [ShardingCompileOnlyTest.java](/akka-cluster-sharding-typed/src/test/java/jdocs/akka/cluster/sharding/typed/ShardingCompileOnlyTest.java) { #roles }



## Persistence example

When using sharding, entities can be moved to different nodes in the cluster. Persistence can be used to recover the state of
an actor after it has moved.

Akka Persistence is based on the single-writer principle, for a particular `PersistenceId` only one persistent actor
instance should be active. If multiple instances were to persist events at the same time, the events would be
interleaved and might not be interpreted correctly on replay. Cluster Sharding is typically used together with
persistence to ensure that there is only one active entity for each `PersistenceId` (`entityId`).

Here is an example of a persistent actor that is used as a sharded entity:

Scala
:  @@snip [HelloWorldPersistentEntityExample.scala](/akka-cluster-sharding-typed/src/test/scala/docs/akka/cluster/sharding/typed/HelloWorldPersistentEntityExample.scala) { #persistent-entity }

Java
:  @@snip [HelloWorldPersistentEntityExample.java](/akka-cluster-sharding-typed/src/test/java/jdocs/akka/cluster/sharding/typed/HelloWorldPersistentEntityExample.java) { #persistent-entity-import #persistent-entity }

To initialize and use the entity:

Scala
:  @@snip [HelloWorldPersistentEntityExample.scala](/akka-cluster-sharding-typed/src/test/scala/docs/akka/cluster/sharding/typed/HelloWorldPersistentEntityExample.scala) { #persistent-entity-usage }

Java
:  @@snip [HelloWorldPersistentEntityExample.java](/akka-cluster-sharding-typed/src/test/java/jdocs/akka/cluster/sharding/typed/HelloWorldPersistentEntityExample.java) { #persistent-entity-usage-import #persistent-entity-usage }

Note how an unique @apidoc[akka.persistence.typed.PersistenceId] can be constructed from the `EntityTypeKey` and the `entityId`
provided by the @apidoc[typed.*.EntityContext] in the factory function for the `Behavior`. This is a typical way
of defining the `PersistenceId` but other formats are possible, as described in the
@ref:[PersistenceId section](persistence.md#persistenceid).

Sending messages to persistent entities is the same as if the entity wasn't persistent. The only difference is
when an entity is moved the state will be restored. In the above example @ref:[ask](interaction-patterns.md#outside-ask)
is used but `tell` or any of the other @ref:[Interaction Patterns](interaction-patterns.md) can be used.

See @ref:[persistence](persistence.md) for more details.

## Shard allocation

A shard is a group of entities that will be managed together. The grouping is typically defined by a hashing
function of the `entityId`. For a specific entity identifier the shard identifier must always
be the same. Otherwise the entity actor might accidentally be started in several places at the same time.

By default the shard identifier is the absolute value of the `hashCode` of the entity identifier modulo
the total number of shards. The number of shards is configured by:

@@snip [reference.conf](/akka-cluster-sharding-typed/src/main/resources/reference.conf) { #number-of-shards }

As a rule of thumb, the number of shards should be a factor ten greater than the planned maximum number of
cluster nodes. It doesn't have to be exact. Fewer shards than number of nodes will result in that some nodes will
not host any shards. Too many shards will result in less efficient management of the shards, e.g. rebalancing overhead,
and increased latency because the coordinator is involved in the routing of the first message for each
shard.

The `number-of-shards` configuration value must be the same for all nodes in the cluster and that is verified by
configuration check when joining. Changing the value requires stopping all nodes in the cluster.

The shards are allocated to the nodes in the cluster. The decision of where to allocate a shard is done
by a shard allocation strategy. 

The default implementation `LeastShardAllocationStrategy` allocates new shards to the `ShardRegion` (node) with least
number of previously allocated shards. This strategy can be replaced by an application specific implementation.

When a node is added to the cluster the shards on the existing nodes will be rebalanced to the new node.
The `LeastShardAllocationStrategy` picks shards for rebalancing from the `ShardRegion`s with most number
of previously allocated shards. They will then be allocated to the `ShardRegion` with least number of
previously allocated shards, i.e. new members in the cluster. The amount of shards to rebalance in each
round can be limited to make it progress slower since rebalancing too many shards at the same time could
result in additional load on the system. For example, causing many Event Sourced entites to be started
at the same time.

A new rebalance algorithm was included in Akka 2.6.10. It can reach optimal balance in a few rebalance rounds
(typically 1 or 2 rounds). For backwards compatibility the new algorithm is not enabled by default.
The new algorithm is recommended and will become the default in future versions of Akka.
You enable the new algorithm by setting `rebalance-absolute-limit` > 0, for example:

```
akka.cluster.sharding.least-shard-allocation-strategy.rebalance-absolute-limit = 20
``` 

The `rebalance-absolute-limit` is the maximum number of shards that will be rebalanced in one rebalance round.

You may also want to tune the `akka.cluster.sharding.least-shard-allocation-strategy.rebalance-relative-limit`.
The `rebalance-relative-limit` is a fraction (< 1.0) of total number of (known) shards that will be rebalanced
in one rebalance round. The lower result of `rebalance-relative-limit` and `rebalance-absolute-limit` will be used.

### External shard allocation

An alternative allocation strategy is the @apidoc[ExternalShardAllocationStrategy] which allows
explicit control over where shards are allocated via the @apidoc[ExternalShardAllocation] extension.
This can be used, for example, to match up Kafka Partition consumption with shard locations.

To use it set it as the allocation strategy on your `Entity`:

Scala
: @@snip [ExternalShardAllocationCompileOnlySpec](/akka-cluster-sharding-typed/src/test/scala/docs/akka/cluster/sharding/typed/ExternalShardAllocationCompileOnlySpec.scala) { #entity }

Java
: @@snip [ExternalShardAllocationCompileOnlyTest](/akka-cluster-sharding-typed/src/test/java/jdocs/akka/cluster/sharding/typed/ExternalShardAllocationCompileOnlyTest.java) { #entity }

For any shardId that has not been allocated it will be allocated to the requesting node. To make explicit allocations:

Scala
: @@snip [ExternalShardAllocationCompileOnlySpec](/akka-cluster-sharding-typed/src/test/scala/docs/akka/cluster/sharding/typed/ExternalShardAllocationCompileOnlySpec.scala) { #client }

Java
: @@snip [ExternalShardAllocationCompileOnlyTest](/akka-cluster-sharding-typed/src/test/java/jdocs/akka/cluster/sharding/typed/ExternalShardAllocationCompileOnlyTest.java) { #client }

Any new or moved shard allocations will be moved on the next rebalance.

The communication from the client to the shard allocation strategy is via @ref[Distributed Data](./distributed-data.md).
It uses a single `LWWMap` that can support 10s of thousands of shards. Later versions could use multiple keys to 
support a greater number of shards.

#### Example project for external allocation strategy

@extref[Kafka to Cluster Sharding](samples:akka-samples-kafka-to-sharding)
is an example project that can be downloaded, and with instructions of how to run, that demonstrates how to use
external sharding to co-locate Kafka partition consumption with shards.

### Custom shard allocation

An optional custom shard allocation strategy can be passed into the optional parameter when initializing an entity type 
or explicitly using the `withAllocationStrategy` function.
See the API documentation of @scala[`akka.cluster.sharding.ShardAllocationStrategy`]@java[`akka.cluster.sharding.AbstractShardAllocationStrategy`] for details of how to implement a custom `ShardAllocationStrategy`.

## How it works

See @ref:[Cluster Sharding concepts](cluster-sharding-concepts.md).


## Passivation

If the state of the entities are persistent you may stop entities that are not used to
reduce memory consumption. This is done by the application specific implementation of
the entity actors for example by defining receive timeout (`context.setReceiveTimeout`).
If a message is already enqueued to the entity when it stops itself the enqueued message
in the mailbox will be dropped. To support graceful passivation without losing such
messages the entity actor can send `ClusterSharding.Passivate` to the
@scala[`ActorRef[ShardCommand]`]@java[`ActorRef<ShardCommand>`] that was passed in to
the factory method when creating the entity. The optional `stopMessage` message
will be sent back to the entity, which is then supposed to stop itself, otherwise it will
be stopped automatically. Incoming messages will be buffered by the `Shard` between reception
of `Passivate` and termination of the entity. Such buffered messages are thereafter delivered
to a new incarnation of the entity.

Scala
:  @@snip [ShardingCompileOnlySpec.scala](/akka-cluster-sharding-typed/src/test/scala/docs/akka/cluster/sharding/typed/ShardingCompileOnlySpec.scala) { #counter-passivate }

Java
:  @@snip [ShardingCompileOnlyTest.java](/akka-cluster-sharding-typed/src/test/java/jdocs/akka/cluster/sharding/typed/ShardingCompileOnlyTest.java) { #counter-passivate }

and then initialized with:

Scala
:  @@snip [ShardingCompileOnlySpec.scala](/akka-cluster-sharding-typed/src/test/scala/docs/akka/cluster/sharding/typed/ShardingCompileOnlySpec.scala) { #counter-passivate-init }

Java
:  @@snip [ShardingCompileOnlyTest.java](/akka-cluster-sharding-typed/src/test/java/jdocs/akka/cluster/sharding/typed/ShardingCompileOnlyTest.java) { #counter-passivate-init }

Note that in the above example the `stopMessage` is specified as `GoodByeCounter`. That message will be sent to
the entity when it's supposed to stop itself due to rebalance or passivation. If the `stopMessage` is not defined
it will be stopped automatically without receiving a specific message. It can be useful to define a custom stop
message if the entity needs to perform some asynchronous cleanup or interactions before stopping.

### Automatic Passivation

The entities are automatically passivated if they haven't received a message within the duration configured in
`akka.cluster.sharding.passivate-idle-entity-after` 
or by explicitly setting the `passivateIdleEntityAfter` flag on `ClusterShardingSettings` to a suitable
time to keep the actor alive. Note that only messages sent through sharding are counted, so direct messages
to the `ActorRef` or messages that the actor sends to itself are not counted in this activity.
Passivation can be disabled by setting `akka.cluster.sharding.passivate-idle-entity-after = off`.
It is disabled automatically if @ref:[Remembering Entities](#remembering-entities) is enabled.

## Sharding State 

There are two types of state managed:

1. @ref:[ShardCoordinator State](cluster-sharding-concepts.md#shardcoordinator-state) - the `Shard` locations. This is stored in the `State Store`.
1. @ref:[Remembering Entities](#remembering-entities) - the active shards and the entities in each `Shard`, which is optional, and disabled by default. This is stored in the `Remember Entities Store`. 
 

### State Store

A state store is mandatory for sharding, it contains the location of shards. The `ShardCoordinator` needs to load this state after
it moves between nodes.

There are two options for the state store:

* @ref:[Distributed Data Mode](#distributed-data-mode) - uses Akka @ref:[Distributed Data](distributed-data.md) (CRDTs) (the default)
* @ref:[Persistence Mode](#persistence-mode) - (deprecated) uses Akka @ref:[Persistence](persistence.md) (Event Sourcing)

@@include[cluster.md](../includes/cluster.md) { #sharding-persistence-mode-deprecated }

#### Distributed Data Mode

To enable distributed data store mode (the default):

```
akka.cluster.sharding.state-store-mode = ddata
```

The state of the `ShardCoordinator` is replicated across the cluster but is not stored to disk.
@ref:[Distributed Data](distributed-data.md) handles the `ShardCoordinator`'s state with `WriteMajorityPlus`/`ReadMajorityPlus` consistency.
When all nodes in the cluster have been stopped, the state is no longer needed and dropped.

Cluster Sharding uses its own Distributed Data `Replicator` per node. 
If using roles with sharding there is one `Replicator` per role, which enables a subset of
all nodes for some entity types and another subset for other entity types. Each replicator has a name
that contains the node role and therefore the role configuration must be the same on all nodes in the
cluster, for example you can't change the roles when performing a rolling update.
Changing roles requires @ref:[a full cluster restart](../additional/rolling-updates.md#cluster-sharding-configuration-change).

The `akka.cluster.sharding.distributed-data` config section configures the settings for Distributed Data. 
It's not possible to have different `distributed-data` settings for different sharding entity types.

#### Persistence mode

To enable persistence store mode:

```
akka.cluster.sharding.state-store-mode = persistence
```

Since it is running in a cluster @ref:[Persistence](persistence.md) must be configured with a distributed journal.

@@@ warning 

Persistence mode for @ref:[Remembering Entities](#remembering-entities) has been replaced by a remember entities state mode. It should not be
used for new projects and existing projects should migrate as soon as possible.

@@@

## Remembering Entities

Remembering entities automatically restarts entities after a rebalance or entity crash. 
Without remembered entities restarts happen on the arrival of a message.

Enabling remembered entities disables @ref:[Automatic Passivation](#passivation).

The state of the entities themselves is not restored unless they have been made persistent,
for example with @ref:[Event Sourcing](persistence.md).

To enable remember entities set `rememberEntities` flag to true in
`ClusterShardingSettings` when starting a shard region (or its proxy) for a given `entity` type or configure
`akka.cluster.sharding.remember-entities = on`.

Starting and stopping entities has an overhead but this is limited by batching operations to the
underlying remember entities store.

### Behavior When Enabled 

When `rememberEntities` is enabled, whenever a `Shard` is rebalanced onto another
node or recovers after a crash, it will recreate all the entities which were previously
running in that `Shard`. 

To permanently stop entities send a `ClusterSharding.Passivate` to the
@scala[`ActorRef[ShardCommand]`]@java[`ActorRef<ShardCommand>`] that was passed in to
the factory method when creating the entity.
Otherwise, the entity will be automatically restarted after the entity restart backoff specified in the configuration.

### Remember entities store

There are two options for the remember entities store:

1. `ddata` 
1. `eventsourced` 

#### Remember entities distributed data mode

Enable ddata mode with (enabled by default):

```
akka.cluster.sharding.remember-entities-store = ddata
```

To support restarting entities after a full cluster restart (non-rolling) the remember entities store is persisted to disk by distributed data.
This can be disabled if not needed:
```
akka.cluster.sharding.distributed-data.durable.keys = []
```

Reasons for disabling:

* No requirement for remembering entities after a full cluster shutdown
* Running in an environment without access to disk between restarts e.g. Kubernetes without persistent volumes

For supporting remembered entities in an environment without disk storage use `eventsourced` mode instead.

#### Event sourced mode

Enable `eventsourced` mode with:

```
akka.cluster.sharding.remember-entities-store = eventsourced
```

This mode uses @ref:[Event Sourcing](./persistence.md) to store the active shards and active entities for each shard 
so a persistence and snapshot plugin must be configured.

```
akka.cluster.sharding.journal-plugin-id = <plugin>
akka.cluster.sharding.snapshot-plugin-id = <plugin>
```

### Migrating from deprecated persistence mode

If not using remembered entities you can migrate to ddata with a full cluster restart.

If using remembered entities there are two migration options: 

* `ddata` for the state store and `ddata` for remembering entities. All remembered entities will be lost after a full cluster restart.
* `ddata` for the state store and `eventsourced` for remembering entities. The new `eventsourced` remembering entities store 
   reads the data written by the old `persistence` mode. Your remembered entities will be remembered after a full cluster restart. 

For migrating existing remembered entities an event adapter needs to be configured in the config for the journal you use in your `application.conf`.
In this example `cassandra` is the used journal:

```
akka.persistence.cassandra.journal {
  event-adapters {
    coordinator-migration = "akka.cluster.sharding.OldCoordinatorStateMigrationEventAdapter"
  }

  event-adapter-bindings {
    "akka.cluster.sharding.ShardCoordinator$Internal$DomainEvent" = coordinator-migration
  }
}
```

Once you have migrated you cannot go back to the old persistence store, a rolling update is therefore not possible.

When @ref:[Distributed Data mode](#distributed-data-mode) is used the identifiers of the entities are
stored in @ref:[Durable Storage](distributed-data.md#durable-storage) of Distributed Data. You may want to change the
configuration of the `akka.cluster.sharding.distributed-data.durable.lmdb.dir`, since
the default directory contains the remote port of the actor system. If using a dynamically
assigned port (0) it will be different each time and the previously stored data will not
be loaded.

The reason for storing the identifiers of the active entities in durable storage, i.e. stored to
disk, is that the same entities should be started also after a complete cluster restart. If this is not needed
you can disable durable storage and benefit from better performance by using the following configuration:

```
akka.cluster.sharding.distributed-data.durable.keys = []
```
## Startup after minimum number of members

It's recommended to use Cluster Sharding with the Cluster setting `akka.cluster.min-nr-of-members` or
`akka.cluster.role.<role-name>.min-nr-of-members`. `min-nr-of-members` will defer the allocation of the shards
until at least that number of regions have been started and registered to the coordinator. This
avoids that many shards are allocated to the first region that registers and only later are
rebalanced to other nodes.

See @ref:[How To Startup when Cluster Size Reached](cluster.md#how-to-startup-when-a-cluster-size-is-reached)
for more information about `min-nr-of-members`.

## Inspecting cluster sharding state

Two requests to inspect the cluster state are available:

@apidoc[akka.cluster.sharding.typed.GetShardRegionState] which will reply with a 
@apidoc[ShardRegion.CurrentShardRegionState] that contains the identifiers of the shards running in
a Region and what entities are alive for each of them.

Scala
:  @@snip [ShardingCompileOnlySpec.scala](/akka-cluster-sharding-typed/src/test/scala/docs/akka/cluster/sharding/typed/ShardingCompileOnlySpec.scala) { #get-shard-region-state }

Java
:  @@snip [ShardingCompileOnlyTest.java](/akka-cluster-sharding-typed/src/test/java/jdocs/akka/cluster/sharding/typed/ShardingCompileOnlyTest.java) { #get-shard-region-state }

@apidoc[akka.cluster.sharding.typed.GetClusterShardingStats] which will query all the regions in the cluster and reply with a
@apidoc[ShardRegion.ClusterShardingStats] containing the identifiers of the shards running in each region and a count
of entities that are alive in each shard.

Scala
:  @@snip [ShardingCompileOnlySpec.scala](/akka-cluster-sharding-typed/src/test/scala/docs/akka/cluster/sharding/typed/ShardingCompileOnlySpec.scala) { #get-cluster-sharding-stats }

Java
:  @@snip [ShardingCompileOnlyTest.java](/akka-cluster-sharding-typed/src/test/java/jdocs/akka/cluster/sharding/typed/ShardingCompileOnlyTest.java) { #get-cluster-sharding-stats }

If any shard queries failed, for example due to timeout if a shard was too busy to reply within the configured `akka.cluster.sharding.shard-region-query-timeout`, 
`ShardRegion.CurrentShardRegionState` and `ShardRegion.ClusterShardingStats` will also include the set of shard identifiers by region that failed.

The purpose of these messages is testing and monitoring, they are not provided to give access to
directly sending messages to the individual entities.

## Lease

A @ref[lease](../coordination.md) can be used as an additional safety measure to ensure a shard 
does not run on two nodes.

Reasons for how this can happen:

* Network partitions without an appropriate downing provider
* Mistakes in the deployment process leading to two separate Akka Clusters
* Timing issues between removing members from the Cluster on one side of a network partition and shutting them down on the other side

A lease can be a final backup that means that each shard won't create child entity actors unless it has the lease. 

To use a lease for sharding set `akka.cluster.sharding.use-lease` to the configuration location
of the lease to use. Each shard will try and acquire a lease with with the name `<actor system name>-shard-<type name>-<shard id>` and
the owner is set to the `Cluster(system).selfAddress.hostPort`.

If a shard can't acquire a lease it will remain uninitialized so messages for entities it owns will
be buffered in the `ShardRegion`. If the lease is lost after initialization the Shard will be terminated.

## Removal of internal Cluster Sharding data

Removal of internal Cluster Sharding data is only relevant for "Persistent Mode".
The Cluster Sharding `ShardCoordinator` stores locations of the shards.
This data is safely be removed when restarting the whole Akka Cluster.
Note that this does not include application data.

There is a utility program `akka.cluster.sharding.RemoveInternalClusterShardingData`
that removes this data.

@@@ warning

Never use this program while there are running Akka Cluster nodes that are
using Cluster Sharding. Stop all Cluster nodes before using this program.

@@@

It can be needed to remove the data if the Cluster Sharding coordinator
cannot startup because of corrupt data, which may happen if accidentally
two clusters were running at the same time, e.g. caused by an invalid downing
provider when there was a network partition.

Use this program as a standalone Java main program:

```
java -classpath <jar files, including akka-cluster-sharding>
  akka.cluster.sharding.RemoveInternalClusterShardingData
    -2.3 entityType1 entityType2 entityType3
```

The program is included in the `akka-cluster-sharding` jar file. It
is easiest to run it with same classpath and configuration as your ordinary
application. It can be run from sbt or Maven in similar way.

Specify the entity type names (same as you use in the `init` method
of `ClusterSharding`) as program arguments.

If you specify `-2.3` as the first program argument it will also try
to remove data that was stored by Cluster Sharding in Akka 2.3.x using
different persistenceId.

## Configuration

The `ClusterSharding` extension can be configured with the following properties. These configuration
properties are read by the `ClusterShardingSettings` when created with an ActorSystem parameter.
It is also possible to amend the `ClusterShardingSettings` or create it from another config section
with the same layout as below. 

One important configuration property is `number-of-shards` as described in @ref:[Shard allocation](#shard-allocation).

You may also need to tune the configuration properties is `rebalance-absolute-limit` and `rebalance-relative-limit`
as described in @ref:[Shard allocation](#shard-allocation).

@@snip [reference.conf](/akka-cluster-sharding/src/main/resources/reference.conf) { #sharding-ext-config }

@@snip [reference.conf](/akka-cluster-sharding-typed/src/main/resources/reference.conf) { #sharding-ext-config }

## Example project

@java[@extref[Sharding example project](samples:akka-samples-cluster-sharding-java)]
@scala[@extref[Sharding example project](samples:akka-samples-cluster-sharding-scala)]
is an example project that can be downloaded, and with instructions of how to run.

This project contains a KillrWeather sample illustrating how to use Cluster Sharding.
