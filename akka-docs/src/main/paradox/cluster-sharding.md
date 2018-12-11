# Cluster Sharding

## Dependency

To use Cluster Sharding, you must add the following dependency in your project:

@@dependency[sbt,Maven,Gradle] {
  group=com.typesafe.akka
  artifact=akka-cluster-sharding_$scala.binary_version$
  version=$akka.version$
}

## Sample project

You can look at the
@java[@extref[Cluster Sharding example project](samples:akka-samples-cluster-sharding-java)]
@scala[@extref[Cluster Sharding example project](samples:akka-samples-cluster-sharding-scala)]
to see what this looks like in practice.

## Introduction

Cluster sharding is useful when you need to distribute actors across several nodes in the cluster and want to
be able to interact with them using their logical identifier, but without having to care about
their physical location in the cluster, which might also change over time.

It could for example be actors representing Aggregate Roots in Domain-Driven Design terminology.
Here we call these actors "entities". These actors typically have persistent (durable) state,
but this feature is not limited to actors with persistent state.

Cluster sharding is typically used when you have many stateful actors that together consume
more resources (e.g. memory) than fit on one machine. If you only have a few stateful actors
it might be easier to run them on a @ref:[Cluster Singleton](cluster-singleton.md) node.

In this context sharding means that actors with an identifier, so called entities,
can be automatically distributed across multiple nodes in the cluster. Each entity
actor runs only at one place, and messages can be sent to the entity without requiring
the sender to know the location of the destination actor. This is achieved by sending
the messages via a `ShardRegion` actor provided by this extension, which knows how
to route the message with the entity id to the final destination.

Cluster sharding will not be active on members with status @ref:[WeaklyUp](cluster-usage.md#weakly-up)
if that feature is enabled.

@@@ warning

**Don't use Cluster Sharding together with Automatic Downing**,
since it allows the cluster to split up into two separate clusters, which in turn will result
in *multiple shards and entities* being started, one in each separate cluster!
See @ref:[Downing](cluster-usage.md#automatic-vs-manual-downing).

@@@

## An Example

This is how an entity actor may look like:

Scala
:  @@snip [ClusterShardingSpec.scala](/akka-cluster-sharding/src/multi-jvm/scala/akka/cluster/sharding/ClusterShardingSpec.scala) { #counter-actor }

Java
:  @@snip [ClusterShardingTest.java](/akka-docs/src/test/java/jdocs/sharding/ClusterShardingTest.java) { #counter-actor }

The above actor uses event sourcing and the support provided in @scala[`PersistentActor`] @java[`AbstractPersistentActor`] to store its state.
It does not have to be a persistent actor, but in case of failure or migration of entities between nodes it must be able to recover
its state if it is valuable.

Note how the `persistenceId` is defined. The name of the actor is the entity identifier (utf-8 URL-encoded).
You may define it another way, but it must be unique.

When using the sharding extension you are first, typically at system startup on each node
in the cluster, supposed to register the supported entity types with the `ClusterSharding.start`
method. `ClusterSharding.start` gives you the reference which you can pass along.
Please note that `ClusterSharding.start` will start a `ShardRegion` in [proxy only mode](#proxy-only-mode) 
in case if there is no match between the roles of the current cluster node and the role specified in 
`ClusterShardingSettings`.

Scala
:  @@snip [ClusterShardingSpec.scala](/akka-cluster-sharding/src/multi-jvm/scala/akka/cluster/sharding/ClusterShardingSpec.scala) { #counter-start }

Java
:  @@snip [ClusterShardingTest.java](/akka-docs/src/test/java/jdocs/sharding/ClusterShardingTest.java) { #counter-start }

The @scala[`extractEntityId` and `extractShardId` are two] @java[`messageExtractor` defines] application specific @scala[functions] @java[methods] to extract the entity
identifier and the shard identifier from incoming messages.

Scala
:  @@snip [ClusterShardingSpec.scala](/akka-cluster-sharding/src/multi-jvm/scala/akka/cluster/sharding/ClusterShardingSpec.scala) { #counter-extractor }

Java
:  @@snip [ClusterShardingTest.java](/akka-docs/src/test/java/jdocs/sharding/ClusterShardingTest.java) { #counter-extractor }

This example illustrates two different ways to define the entity identifier in the messages:

 * The `Get` message includes the identifier itself.
 * The `EntityEnvelope` holds the identifier, and the actual message that is
sent to the entity actor is wrapped in the envelope.

Note how these two messages types are handled in the @scala[`extractEntityId` function] @java[`entityId` and `entityMessage` methods] shown above.
The message sent to the entity actor is @scala[the second part of the tuple returned by the `extractEntityId`] @java[what `entityMessage` returns] and that makes it possible to unwrap envelopes
if needed.

A shard is a group of entities that will be managed together. The grouping is defined by the
`extractShardId` function shown above. For a specific entity identifier the shard identifier must always
be the same. Otherwise the entity actor might accidentally be started in several places at the same time.

Creating a good sharding algorithm is an interesting challenge in itself. Try to produce a uniform distribution,
i.e. same amount of entities in each shard. As a rule of thumb, the number of shards should be a factor ten greater
than the planned maximum number of cluster nodes. Less shards than number of nodes will result in that some nodes
will not host any shards. Too many shards will result in less efficient management of the shards, e.g. rebalancing
overhead, and increased latency because the coordinator is involved in the routing of the first message for each
shard. The sharding algorithm must be the same on all nodes in a running cluster. It can be changed after stopping
all nodes in the cluster.

A simple sharding algorithm that works fine in most cases is to take the absolute value of the `hashCode` of
the entity identifier modulo number of shards. As a convenience this is provided by the
`ShardRegion.HashCodeMessageExtractor`.

Messages to the entities are always sent via the local `ShardRegion`. The `ShardRegion` actor reference for a
named entity type is returned by `ClusterSharding.start` and it can also be retrieved with `ClusterSharding.shardRegion`.
The `ShardRegion` will lookup the location of the shard for the entity if it does not already know its location. It will
delegate the message to the right node and it will create the entity actor on demand, i.e. when the
first message for a specific entity is delivered.

Scala
:  @@snip [ClusterShardingSpec.scala](/akka-cluster-sharding/src/multi-jvm/scala/akka/cluster/sharding/ClusterShardingSpec.scala) { #counter-usage }

Java
:  @@snip [ClusterShardingTest.java](/akka-docs/src/test/java/jdocs/sharding/ClusterShardingTest.java) { #counter-usage }

@@@ div { .group-scala }

A more comprehensive sample is available in the
tutorial named [Akka Cluster Sharding with Scala!](https://github.com/typesafehub/activator-akka-cluster-sharding-scala).

@@@

## How it works

The `ShardRegion` actor is started on each node in the cluster, or group of nodes
tagged with a specific role. The `ShardRegion` is created with two application specific
functions to extract the entity identifier and the shard identifier from incoming messages.
A `Shard` is a group of entities that will be managed together. For the first message in a
specific shard the `ShardRegion` requests the location of the shard from a central coordinator,
the `ShardCoordinator`.

The `ShardCoordinator` decides which `ShardRegion` shall own the `Shard` and informs
that `ShardRegion`. The region will confirm this request and create the `Shard` supervisor
as a child actor. The individual `Entities` will then be created when needed by the `Shard`
actor. Incoming messages thus travel via the `ShardRegion` and the `Shard` to the target
`Entity`.

If the shard home is another `ShardRegion` instance messages will be forwarded
to that `ShardRegion` instance instead. While resolving the location of a
shard incoming messages for that shard are buffered and later delivered when the
shard home is known. Subsequent messages to the resolved shard can be delivered
to the target destination immediately without involving the `ShardCoordinator`.

### Scenarios

Once a `Shard` location is known `ShardRegion`s send messages directly. Here are the
scenarios for getting to this state. In the scenarios the following notation is used:

* `SC` - ShardCoordinator
* `M#` - Message 1, 2, 3, etc
* `SR#` - ShardRegion 1, 2 3, etc
* `S#` - Shard 1 2 3, etc
* `E#` - Entity 1 2 3, etc. An entity refers to an Actor managed by Cluster Sharding.

Where `#` is a number to distinguish between instances as there are multiple in the Cluster.

#### Scenario 1: Message to an unknown shard that belongs to the local ShardRegion

 1. Incoming message `M1` to `ShardRegion` instance `SR1`.
 2. `M1` is mapped to shard `S1`. `SR1` doesn't know about `S1`, so it asks the `SC` for the location of `S1`.
 3. `SC` answers that the home of `S1` is `SR1`.
 4. `R1` creates child actor for the entity `E1` and sends buffered messages for `S1` to `E1` child
 5. All incoming messages for `S1` which arrive at `R1` can be handled by `R1` without `SC`. It creates entity children as needed, and forwards messages to them.

#### Scenario 2: Message to an unknown shard that belongs to a remote ShardRegion 

 1. Incoming message `M2` to `ShardRegion` instance `SR1`.
 2. `M2` is mapped to `S2`. SR1 doesn't know about `S2`, so it asks `SC` for the location of `S2`.
 3. `SC` answers that the home of `S2` is `SR2`.
 4. `SR1` sends buffered messages for `S2` to `SR2`.
 5. All incoming messages for `S2` which arrive at `SR1` can be handled by `SR1` without `SC`. It forwards messages to `SR2`.
 6. `SR2` receives message for `S2`, ask `SC`, which answers that the home of `S2` is `SR2`, and we are in Scenario 1 (but for `SR2`).
 

### Shard location 

To make sure that at most one instance of a specific entity actor is running somewhere
in the cluster it is important that all nodes have the same view of where the shards
are located. Therefore the shard allocation decisions are taken by the central
`ShardCoordinator`, which is running as a cluster singleton, i.e. one instance on
the oldest member among all cluster nodes or a group of nodes tagged with a specific
role.

The logic that decides where a shard is to be located is defined in a pluggable shard
allocation strategy. The default implementation `ShardCoordinator.LeastShardAllocationStrategy`
allocates new shards to the `ShardRegion` with least number of previously allocated shards.
This strategy can be replaced by an application specific implementation.

### Shard Rebalancing

To be able to use newly added members in the cluster the coordinator facilitates rebalancing
of shards, i.e. migrate entities from one node to another. In the rebalance process the
coordinator first notifies all `ShardRegion` actors that a handoff for a shard has started.
That means they will start buffering incoming messages for that shard, in the same way as if the
shard location is unknown. During the rebalance process the coordinator will not answer any
requests for the location of shards that are being rebalanced, i.e. local buffering will
continue until the handoff is completed. The `ShardRegion` responsible for the rebalanced shard
will stop all entities in that shard by sending the specified `stopMessage`
(default `PoisonPill`) to them. When all entities have been terminated the `ShardRegion`
owning the entities will acknowledge the handoff as completed to the coordinator.
Thereafter the coordinator will reply to requests for the location of
the shard and thereby allocate a new home for the shard and then buffered messages in the
`ShardRegion` actors are delivered to the new location. This means that the state of the entities
are not transferred or migrated. If the state of the entities are of importance it should be
persistent (durable), e.g. with @ref:[Persistence](persistence.md), so that it can be recovered at the new
location.

The logic that decides which shards to rebalance is defined in a pluggable shard
allocation strategy. The default implementation `ShardCoordinator.LeastShardAllocationStrategy`
picks shards for handoff from the `ShardRegion` with most number of previously allocated shards.
They will then be allocated to the `ShardRegion` with least number of previously allocated shards,
i.e. new members in the cluster.

For the `LeastShardAllocationStrategy` there is a configurable threshold (`rebalance-threshold`) of
how large the difference must be to begin the rebalancing. The difference between number of shards in
the region with most shards and the region with least shards must be greater than the `rebalance-threshold`
for the rebalance to occur.

A `rebalance-threshold` of 1 gives the best distribution and therefore typically the best choice.
A higher threshold means that more shards can be rebalanced at the same time instead of one-by-one.
That has the advantage that the rebalance process can be quicker but has the drawback that the
the number of shards (and therefore load) between different nodes may be significantly different.

### Shard Coordinator State

The state of shard locations in the `ShardCoordinator` is persistent (durable) with
@ref:[Distributed Data](distributed-data.md) or @ref:[Persistence](persistence.md) to survive failures. When a crashed or
unreachable coordinator node has been removed (via down) from the cluster a new `ShardCoordinator` singleton
actor will take over and the state is recovered. During such a failure period shards
with known location are still available, while messages for new (unknown) shards
are buffered until the new `ShardCoordinator` becomes available.

### Message ordering

As long as a sender uses the same `ShardRegion` actor to deliver messages to an entity
actor the order of the messages is preserved. As long as the buffer limit is not reached
messages are delivered on a best effort basis, with at-most once delivery semantics,
in the same way as ordinary message sending. Reliable end-to-end messaging, with
at-least-once semantics can be added by using `AtLeastOnceDelivery`  in @ref:[Persistence](persistence.md).

### Overhead

Some additional latency is introduced for messages targeted to new or previously
unused shards due to the round-trip to the coordinator. Rebalancing of shards may
also add latency. This should be considered when designing the application specific
shard resolution, e.g. to avoid too fine grained shards. Once a shard's location is known
the only overhead is sending a message via the `ShardRegion` rather than directly.

<a id="cluster-sharding-mode"></a>
## Distributed Data vs. Persistence Mode

The state of the coordinator and the state of [Remembering Entities](#cluster-sharding-remembering) of the shards
are persistent (durable) to survive failures. @ref:[Distributed Data](distributed-data.md) or @ref:[Persistence](persistence.md)
can be used for the storage. Distributed Data is used by default.

The functionality when using the two modes is the same. If your sharded entities are not using Akka Persistence
themselves it is more convenient to use the Distributed Data mode, since then you don't have to
setup and operate a separate data store (e.g. Cassandra) for persistence. Aside from that, there are
no major reasons for using one mode over the the other.

It's important to use the same mode on all nodes in the cluster, i.e. it's not possible to perform
a rolling upgrade to change this setting.

### Distributed Data Mode

This mode is enabled with configuration (enabled by default):

```
akka.cluster.sharding.state-store-mode = ddata
```

The state of the `ShardCoordinator` will be replicated inside a cluster by the
@ref:[Distributed Data](distributed-data.md) module with `WriteMajority`/`ReadMajority` consistency.
The state of the coordinator is not durable, it's not stored to disk. When all nodes in
the cluster have been stopped the state is lost and not needed any more.

The state of [Remembering Entities](#cluster-sharding-remembering) is also durable, i.e. it is stored to
disk. The stored entities are started also after a complete cluster restart.

Cluster Sharding is using its own Distributed Data `Replicator` per node role. In this way you can use a subset of
all nodes for some entity types and another subset for other entity types. Each such replicator has a name
that contains the node role and therefore the role configuration must be the same on all nodes in the
cluster, i.e. you can't change the roles when performing a rolling upgrade.

The settings for Distributed Data is configured in the the section
`akka.cluster.sharding.distributed-data`. It's not possible to have different
`distributed-data` settings for different sharding entity types.

### Persistence Mode

This mode is enabled with configuration:

```
akka.cluster.sharding.state-store-mode = persistence
```

Since it is running in a cluster @ref:[Persistence](persistence.md) must be configured with a distributed journal.

## Startup after minimum number of members

It's good to use Cluster Sharding with the Cluster setting `akka.cluster.min-nr-of-members` or
`akka.cluster.role.<role-name>.min-nr-of-members`. That will defer the allocation of the shards
until at least that number of regions have been started and registered to the coordinator. This
avoids that many shards are allocated to the first region that registers and only later are
rebalanced to other nodes.

See @ref:[How To Startup when Cluster Size Reached](cluster-usage.md#min-members) for more information about `min-nr-of-members`.

## Proxy Only Mode

The `ShardRegion` actor can also be started in proxy only mode, i.e. it will not
host any entities itself, but knows how to delegate messages to the right location.
A `ShardRegion` is started in proxy only mode with the `ClusterSharding.startProxy` method.
Also a `ShardRegion` is started in proxy only mode in case if there is no match between the
roles of the current cluster node and the role specified in `ClusterShardingSettings` 
passed to the `ClusterSharding.start` method.

## Passivation

If the state of the entities are persistent you may stop entities that are not used to
reduce memory consumption. This is done by the application specific implementation of
the entity actors for example by defining receive timeout (`context.setReceiveTimeout`).
If a message is already enqueued to the entity when it stops itself the enqueued message
in the mailbox will be dropped. To support graceful passivation without losing such
messages the entity actor can send `ShardRegion.Passivate` to its parent `Shard`.
The specified wrapped message in `Passivate` will be sent back to the entity, which is
then supposed to stop itself. Incoming messages will be buffered by the `Shard`
between reception of `Passivate` and termination of the entity. Such buffered messages
are thereafter delivered to a new incarnation of the entity.

### Automatic Passivation

The entities can be configured to be automatically passivated if they haven't received
a message for a while using the `akka.cluster.sharding.passivate-idle-entity-after` setting,
or by explicitly setting `ClusterShardingSettings.passivateIdleEntityAfter` to a suitable
time to keep the actor alive. Note that only messages sent through sharding are counted, so direct messages
to the `ActorRef` of the actor or messages that it sends to itself are not counted as activity. 
By default automatic passivation is disabled. 

<a id="cluster-sharding-remembering"></a>
## Remembering Entities

The list of entities in each `Shard` can be made persistent (durable) by setting
the `rememberEntities` flag to true in `ClusterShardingSettings` when calling
`ClusterSharding.start` and making sure the `shardIdExtractor` handles
`Shard.StartEntity(EntityId)` which implies that a `ShardId` must be possible to
extract from the `EntityId`.

Scala
:  @@snip [ClusterShardingSpec.scala](/akka-cluster-sharding/src/multi-jvm/scala/akka/cluster/sharding/ClusterShardingSpec.scala) { #extractShardId-StartEntity }

Java
:  @@snip [ClusterShardingTest.java](/akka-docs/src/test/java/jdocs/sharding/ClusterShardingTest.java) { #extractShardId-StartEntity }

When configured to remember entities, whenever a `Shard` is rebalanced onto another
node or recovers after a crash it will recreate all the entities which were previously
running in that `Shard`. To permanently stop entities, a `Passivate` message must be
sent to the parent of the entity actor, otherwise the entity will be automatically
restarted after the entity restart backoff specified in the configuration.

When [Distributed Data mode](#cluster-sharding-mode) is used the identifiers of the entities are
stored in @ref:[Durable Storage](distributed-data.md#ddata-durable) of Distributed Data. You may want to change the
configuration of the `akka.cluster.sharding.distributed-data.durable.lmdb.dir`, since
the default directory contains the remote port of the actor system. If using a dynamically
assigned port (0) it will be different each time and the previously stored data will not
be loaded.

When `rememberEntities` is set to false, a `Shard` will not automatically restart any entities
after a rebalance or recovering from a crash. Entities will only be started once the first message
for that entity has been received in the `Shard`. Entities will not be restarted if they stop without
using a `Passivate`.

Note that the state of the entities themselves will not be restored unless they have been made persistent,
e.g. with @ref:[Persistence](persistence.md).

The performance cost of `rememberEntities` is rather high when starting/stopping entities and when
shards are rebalanced. This cost increases with number of entities per shard and we currently don't
recommend using it with more than 10000 entities per shard.

## Supervision

If you need to use another `supervisorStrategy` for the entity actors than the default (restarting) strategy
you need to create an intermediate parent actor that defines the `supervisorStrategy` to the
child entity actor.

Scala
:  @@snip [ClusterShardingSpec.scala](/akka-cluster-sharding/src/multi-jvm/scala/akka/cluster/sharding/ClusterShardingSpec.scala) { #supervisor }

Java
:  @@snip [ClusterShardingTest.java](/akka-docs/src/test/java/jdocs/sharding/ClusterShardingTest.java) { #supervisor }

You start such a supervisor in the same way as if it was the entity actor.

Scala
:  @@snip [ClusterShardingSpec.scala](/akka-cluster-sharding/src/multi-jvm/scala/akka/cluster/sharding/ClusterShardingSpec.scala) { #counter-supervisor-start }

Java
:  @@snip [ClusterShardingTest.java](/akka-docs/src/test/java/jdocs/sharding/ClusterShardingTest.java) { #counter-supervisor-start }

Note that stopped entities will be started again when a new message is targeted to the entity.

## Graceful Shutdown

You can send the @scala[`ShardRegion.GracefulShutdown`] @java[`ShardRegion.gracefulShutdownInstance`] message
to the `ShardRegion` actor to hand off all shards that are hosted by that `ShardRegion` and then the
`ShardRegion` actor will be stopped. You can `watch` the `ShardRegion` actor to know when it is completed.
During this period other regions will buffer messages for those shards in the same way as when a rebalance is
triggered by the coordinator. When the shards have been stopped the coordinator will allocate these shards elsewhere.

This is performed automatically by the @ref:[Coordinated Shutdown](actors.md#coordinated-shutdown) and is therefore part of the
graceful leaving process of a cluster member.

<a id="removeinternalclustershardingdata"></a>
## Removal of Internal Cluster Sharding Data

The Cluster Sharding coordinator stores the locations of the shards using Akka Persistence.
This data can safely be removed when restarting the whole Akka Cluster.
Note that this is not application data.

There is a utility program `akka.cluster.sharding.RemoveInternalClusterShardingData`
that removes this data.

@@@ warning

Never use this program while there are running Akka Cluster nodes that are
using Cluster Sharding. Stop all Cluster nodes before using this program.

@@@

It can be needed to remove the data if the Cluster Sharding coordinator
cannot startup because of corrupt data, which may happen if accidentally
two clusters were running at the same time, e.g. caused by using auto-down
and there was a network partition.

@@@ warning

**Don't use Cluster Sharding together with Automatic Downing**,
since it allows the cluster to split up into two separate clusters, which in turn will result
in *multiple shards and entities* being started, one in each separate cluster!
See @ref:[Downing](cluster-usage.md#automatic-vs-manual-downing).

@@@

Use this program as a standalone Java main program:

```
java -classpath <jar files, including akka-cluster-sharding>
  akka.cluster.sharding.RemoveInternalClusterShardingData
    -2.3 entityType1 entityType2 entityType3
```

The program is included in the `akka-cluster-sharding` jar file. It
is easiest to run it with same classpath and configuration as your ordinary
application. It can be run from sbt or Maven in similar way.

Specify the entity type names (same as you use in the `start` method
of `ClusterSharding`) as program arguments.

If you specify `-2.3` as the first program argument it will also try
to remove data that was stored by Cluster Sharding in Akka 2.3.x using
different persistenceId.

## Configuration

The `ClusterSharding` extension can be configured with the following properties. These configuration
properties are read by the `ClusterShardingSettings` when created with a `ActorSystem` parameter.
It is also possible to amend the `ClusterShardingSettings` or create it from another config section
with the same layout as below. `ClusterShardingSettings` is a parameter to the `start` method of
the `ClusterSharding` extension, i.e. each each entity type can be configured with different settings
if needed.

@@snip [reference.conf](/akka-cluster-sharding/src/main/resources/reference.conf) { #sharding-ext-config }

Custom shard allocation strategy can be defined in an optional parameter to
`ClusterSharding.start`. See the API documentation of @scala[`ShardAllocationStrategy`] @java[`AbstractShardAllocationStrategy`] for details
of how to implement a custom shard allocation strategy.

## Inspecting cluster sharding state

Two requests to inspect the cluster state are available:

@scala[`ShardRegion.GetShardRegionState`] @java[`ShardRegion.getShardRegionStateInstance`] which will return
a @scala[`ShardRegion.CurrentShardRegionState`] @java[`ShardRegion.ShardRegionState`] that contains
the identifiers of the shards running in a Region and what entities are alive for each of them.

`ShardRegion.GetClusterShardingStats` which will query all the regions in the cluster and return
a `ShardRegion.ClusterShardingStats` containing the identifiers of the shards running in each region and a count
of entities that are alive in each shard.

The type names of all started shards can be acquired via @scala[`ClusterSharding.shardTypeNames`]  @java[`ClusterSharding.getShardTypeNames`].

The purpose of these messages is testing and monitoring, they are not provided to give access to
directly sending messages to the individual entities.

## Rolling upgrades

When doing rolling upgrades special care must be taken to not change any of the following aspects of sharding:

 * the `extractShardId` function
 * the role that the shard regions run on
 * the persistence mode

 If any one of these needs a change it will require a full cluster restart.
