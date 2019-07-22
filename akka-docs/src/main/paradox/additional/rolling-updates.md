# Rolling Updates

A rolling update is the process of replacing one version of the system with another without downtime.
The changes can be new code, changed dependencies such as new Akka version, or modified configuration.

In Akka, rolling updates are typically used for a stateful Akka Cluster where you can't run two separate clusters in
parallel during the update, for example in blue green deployments.

For rolling updates related to Akka dependency version upgrades and the migration guides, please see
@ref:[Rolling Updates and Akka versions](../project/rolling-update.md)

#### This document covers: 
* [Serialization Compatibility](#serialization-compatibility)
* [Cluster Sharding](#cluster-sharding)
* [Cluster Singleton](#cluster-singleton)
* [Migrating Untyped to Typed](#migrating-untyped-to-typed)
* [Cluster Shutdown](#cluster-shutdown)
* [Cluster Configuration Compatibility Check](#cluster-configuration-compatibility-check)
 
## Serialization Compatibility

There are two parts of Akka that need careful consideration when performing an rolling update.

1. Compatibility of remote message protocols. Old nodes may send messages to new nodes and vice versa.
1. Serialization format of persisted events and snapshots. New nodes must be able to read old data, and
   during the update old nodes must be able to read data stored by new nodes.

There are many more application specific aspects for serialization changes during rolling upgrades to consider. 
For example, whether to allow dropped messages or tear down the TCP connection when the manifest is unknown.

* When some message loss during a rolling upgrade is acceptable versus a full shutdown and restart, assuming the application recovers afterwards 
    - If a `java.io.NotSerializableException` is thrown in `fromBinary` this is treated as a transient problem, the issue logged and the message is dropped
    - If other exceptions are thrown it can be an indication of corrupt bytes from the underlying transport, and the connection is broken
* For more zero-impact rolling upgrades, it is important to consider a strategy for serialization format that can be evolved. You can find advice in
@ref:[Persistence - Schema Evolution](../persistence-schema-evolution.md), which also applies to
remote messages when deploying with rolling updates.

One approach to retiring a serializer without downtime is carried out in @ref:[two rolling upgrade steps to switch to the new serializer](../serialization.md#rolling-upgrades). 

## Cluster Sharding

During a rolling upgrade, sharded entities receiving traffic may be moved during @ref:[shard rebalancing](../cluster-sharding.md#shard-rebalancing), 
to an old or new node in the cluster, based on the pluggable allocation strategy and settings.
When an old node is stopped the shards that were running on it may be allocated to one of the
other old nodes remaining in the cluster. See @ref[ClusterSingleton](#cluster-singleton) for a useful `ShardCoordinator` optimization.

There are some cases when @ref:[a full cluster restart is needed](../cluster-sharding.md#rolling-upgrades).

## Cluster Singleton

It's more efficient to avoid moving a `ClusterSingleton` more than necessary because they typically have to recover their state
and it might introduce unnecessary delays.

An optional optimization is to leave the oldest node running a `ClusterSingleton` until last
to avoid it having to move more than once. 

## Migrating Untyped to Typed

It is recommended with a two step approach:

* Deploy with the new nodes set to `akka.cluster.configuration-compatibility-check.enforce-on-join = off`
and ensure all nodes are in this state
* Deploy again and with the new nodes set to `akka.cluster.configuration-compatibility-check.enforce-on-join = on`. 

The configuration from existing nodes should pass the @ref:[Cluster Configuration Compatibility Checks](#cluster-configuration-compatibility-check).
Find out more about coexisting and @ref:[untyped to typed](../typed/coexisting.md#untyped-to-typed). 

### With Cluster Sharding and Persistence

Rolling upgrades where shards on old nodes are running untyped persistent actors 
and new ones are running typed persistent behaviors have been tested successfully by the team and users.
Samples coming soon.

## Cluster Shutdown
 
@ref:[Coordinated Shutdown](../actors.md#coordinated-shutdown) will automatically run on SIGTERM when the cluster node sees itself as Exiting.
Thus running shutdown tasks in a JVM shutdown hook is not recommended.
@ref:[Graceful shutdown](../cluster-sharding.md#graceful-shutdown) of Cluster Singletons and Cluster Sharding similarly happen automatically.
 
In case of network failures it may still be necessary to set the node’s status to Down in order to complete the removal. 

Find out more about
* @ref:[Cluster Downing](../cluster-usage.md#downing) and providers
* [Cluster Bootstrap](https://doc.akka.io/docs/akka-management/current/bootstrap/index.html#rolling-updates) and Rolling updates
* [Split Brain Resolver](https://doc.akka.io/docs/akka-enhancements/current/split-brain-resolver.html)

## Cluster Configuration Compatibility Checks

Relevant information on rolling updates and enforcing @ref:[Akka Cluster configuration compatibility checks](../cluster-usage.md#configuration-compatibility-check)
on joining nodes.
