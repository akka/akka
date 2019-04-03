# Rolling Updates

## Introduction

A rolling update is the process of replacing one version of the system with another without downtime.
The changes can be new code, changed dependencies such as new Akka version, or modified configuration.
Rolling updates are typically used for a stateful Akka Cluster where you can't run two separate clusters in
parallel during the update as in blue green deployments.

There are two parts of Akka that need careful consideration when performing an rolling update.

1. Compatibility of remote message protocols. Old nodes may send messages to new nodes and vice versa.
1. Serialization format of persisted events and snapshots. New nodes must be able to read old data, and
   during the update old nodes must be able to read data stored by new nodes.

There are many more application specific aspects. It's important to have a strategy for serialization
format that can be evolved and you can find advice in
@ref:[Persistence - Schema Evolution](../persistence-schema-evolution.md), which also applies to
remote messages when deploying with rolling updates.

Akka supports rolling updates between two consecutive patch versions unless an exception is
mentioned on this page. For example updating Akka version from 2.5.15 to 2.5.16. Many times
it is also possible to skip several versions and exceptions to that are also described here.
For example it's possible to update from 2.5.14 to 2.5.16 without intermediate 2.5.15.

It's not supported to have a cluster with more than two different versions. Roll out the first
update completely before starting next update.

Rolling update from classic remoting to Artery is not supported since the protocol
is completely different. It will require a full cluster shutdown and new startup.

## Shutdown order

It's best to keep the node that has been running the longest until last. That is because
Cluster Singletons (including Cluster Sharding coordinators) are running on the oldest nodes. It's more
efficient to avoid moving the singletons more than necessary since those typically have to recover
their state and it might introduce unnecessary delays in for example access to new sharded actors.

## Change log

### 2.5.0 Several changes in minor release

See @ref:[migration guide](migration-guide-2.4.x-2.5.x.md#rolling-update) when updating from 2.4.x to 2.5.x.

### 2.5.10 Joining regression

Issue: [#24622](https://github.com/akka/akka/issues/24622)

Incompatible change was introduced in 2.5.10 and fixed in 2.5.11.

This means that you can't do a rolling update from 2.5.9 to 2.5.10 and must instead do update from 2.5.9 to 2.5.11.

### 2.5.10 Joining old versions

Issue: [#25491](https://github.com/akka/akka/issues/25491)

Incompatibility was introduced in in 2.5.10 and fixed in 2.5.15.

That means that you should do rolling update from 2.5.9 directly to 2.5.15 if you need to be able to
join 2.5.9 nodes during the update phase.

### 2.5.14 Distributed Data serializer for `ORSet[ActorRef]`

Issue: [#23703](https://github.com/akka/akka/issues/23703)

Intentional change was done in 2.5.14.

This change required a two phase update where the data was duplicated to be compatible with both old and new nodes.

* 2.5.13 - old format, before the change. Can communicate with intermediate format and with old format.
* 2.5.14, 2.5.15, 2.5.16 - intermediate format. Can communicate with old format and with new format.
* 2.5.17 - new format. Can communicate with intermediate format and with new format.

This means that you can't update from 2.5.13 directly to 2.5.17. You must first update to one of the intermediate
versions 2.5.14, 2.5.15, or 2.5.16.

### 2.5.22 ClusterSharding serializer for `ShardRegionStats`

Issue: [#25348](https://github.com/akka/akka/issues/25348)

Intentional change was done in 2.5.22.

Changed serializer for classes: `GetShardRegionStats`, `ShardRegionStats`, `GetShardStats`, `ShardStats`

This change required a two phase update where new serializer was introduced but not enabled in an earlier version.

* 2.5.18 - serializer was added but not enabled, `JavaSerializer` still used
* 2.5.22 - `ClusterShardingMessageSerializer` was enabled for these classes

This means that you can't update from 2.5.17 directly to 2.5.22. You must first update to one of the intermediate
versions 2.5.18, 2.5.19, 2.5.20 or 2.5.21.

