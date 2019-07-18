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
* [Migrating Untyped to Typed](#migrating-untyped-to-typed)
* [Cluster Shutdown](#cluster-shutdown)
* [Cluster Configuration Compatibility Check](#cluster-configuration-compatibility-check)
 
## Serialization Compatibility

There are two parts of Akka that need careful consideration when performing an rolling update.

1. Compatibility of remote message protocols. Old nodes may send messages to new nodes and vice versa.
1. Serialization format of persisted events and snapshots. New nodes must be able to read old data, and
   during the update old nodes must be able to read data stored by new nodes.

There are many more application specific aspects. It's important to have a strategy for serialization
format that can be evolved and you can find advice in
@ref:[Persistence - Schema Evolution](../persistence-schema-evolution.md), which also applies to
remote messages when deploying with rolling updates.

### Retiring a serializer without downtime

One approach is carried out in two phases: 

1. Deploy a version of your application that still supports deserializing messages in the old form 
(your deserializer is still in "akka.actor.serializers"-section of your configuration, but it no longer serializes new messages 
(it is removed from “akka.actor.serialization-bindings”-section). See @ref:[Serialization Configuration](../serialization.md#configuration) for details.
1. After this upgrade has succeeded and there are no longer systems sending messages serialized with the decommissioned serializer, 
you can upgrade to a version that also no longer contains this serializer in `akka.actor.serializers`.

## Cluster Sharding

During a rolling upgrade, sharded entities receiving traffic may be moved during [shard rebalancing](../cluster-sharding.html#shard-rebalancing), 
to an old or new node in the cluster, based on the pluggable allocation strategy and settings.
When an old node is stopped the shards that were running on it may be allocated to one of the
other old nodes remaining in the cluster. 
 
It is recommended to leave the oldest node until last, because the shard coordinator 
is running on that node. See [Cluster Shutdown](#cluster-shutdown) below for more detail.

There are some cases when @ref:[a full cluster restart is needed](../cluster-sharding.md#rolling-upgrades).

## Migrating Untyped to Typed

It is recommended with a two step approach:

* Deploy with the new nodes set to `akka.cluster.configuration-compatibility-check.enforce-on-join = off`
and ensure all nodes are in this state
* Deploy again and with the new nodes set to `akka.cluster.configuration-compatibility-check.enforce-on-join = on`. 

The configuration from existing nodes should pass the [Cluster Configuration Compatibility Checks](#cluster-configuration-compatibility-check).
Find out more about coexisting and [untyped to typed](../typed/coexisting.md#untyped-to-typed). 

### With Cluster Sharding and Persistence

Rolling upgrades where shards on old nodes are running untyped persistent actors 
and new ones are running typed persistent behaviors have been tested successfully by the team and users.
Samples coming soon.

## Cluster Shutdown
 
[Coordinated Shutdown](../actors.md#coordinated-shutdown) will automatically run when the cluster node sees itself as Exiting.
[Graceful shutdown](../cluster-sharding.md#graceful-shutdown) of Cluster Singletons and Cluster Sharding similarly happen automatically.
 
But in case of network failures during rolling restarts and updates it 
may still be necessary to set the node’s status to Down in order to complete the removal. 

Find out more about
* [Cluster Downing](../cluster-usage.html#downing) and providers
* [Cluster Bootstrap](https://doc.akka.io/docs/akka-management/current/bootstrap/index.html#rolling-updates) and Rolling updates

### Cluster Shutdown Order

If possible keep the oldest node until last because
Cluster Singletons (including Cluster Sharding coordinators) are running on them. It's more
efficient to avoid moving the singletons more than necessary since those typically have to recover
their state and it might introduce unnecessary delays, for example in access to new sharded actors.

## Cluster Configuration Compatibility Checks

Relevant information on rolling updates and enforcing [Akka Cluster configuration compatibility checks](cluster-usage.md#configuration-compatibility-check)
on joining nodes.
