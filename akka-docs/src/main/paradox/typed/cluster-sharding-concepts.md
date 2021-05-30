# Cluster Sharding concepts

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
 4. `SR1` creates child actor shard `S1` and forwards the message to it.
 5. `S1` creates child actor for `E1` and forwards the message to it.
 6. All incoming messages for `S1` which arrive at `SR1` can be handled by `SR1` without `SC`. 

#### Scenario 2: Message to an unknown shard that belongs to a remote ShardRegion 

 1. Incoming message `M2` to `ShardRegion` instance `SR1`.
 2. `M2` is mapped to `S2`. `SR1` doesn't know about `S2`, so it asks `SC` for the location of `S2`.
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

The logic that decides where a shard is to be located is defined in a
pluggable @ref:[shard allocation strategy](cluster-sharding.md#shard-allocation).

### Shard rebalancing

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
the shard, thereby allocating a new home for the shard, and then buffered messages in the
`ShardRegion` actors are delivered to the new location. This means that the state of the entities
are not transferred or migrated. If the state of the entities are of importance it should be
persistent (durable), e.g. with @ref:[Persistence](persistence.md) (or see @ref:[Classic Persistence](../persistence.md)), so that it can be recovered at the new
location.

The logic that decides which shards to rebalance is defined in a pluggable shard
allocation strategy. The default implementation `LeastShardAllocationStrategy` allocates new shards
to the `ShardRegion` (node) with least number of previously allocated shards. 

See also @ref:[Shard allocation](cluster-sharding.md#shard-allocation).

### ShardCoordinator state

The state of shard locations in the `ShardCoordinator` is persistent (durable) with
@ref:[Distributed Data](distributed-data.md) (or see @ref:[Classic Distributed Data](../distributed-data.md)) to survive failures. 

When a crashed or
unreachable coordinator node has been removed (via down) from the cluster a new `ShardCoordinator` singleton
actor will take over and the state is recovered. During such a failure period shards
with a known location are still available, while messages for new (unknown) shards
are buffered until the new `ShardCoordinator` becomes available.

### Message ordering

As long as a sender uses the same `ShardRegion` actor to deliver messages to an entity
actor the order of the messages is preserved. As long as the buffer limit is not reached
messages are delivered on a best effort basis, with at-most once delivery semantics,
in the same way as ordinary message sending.

### Reliable delivery

Reliable end-to-end messaging, with at-least-once semantics can be added by using the
@ref:[Reliable Delivery](reliable-delivery.md#sharding) feature.

### Overhead

Some additional latency is introduced for messages targeted to new or previously
unused shards due to the round-trip to the coordinator. Rebalancing of shards may
also add latency. This should be considered when designing the application specific
shard resolution, e.g. to avoid too fine grained shards. Once a shard's location is known
the only overhead is sending a message via the `ShardRegion` rather than directly.
