# Rolling Deploys

## Rolling Updates

A rolling update is the process of replacing one version of the system with another without downtime.
The changes can be new code, changed dependencies such as new Akka version, or modified configuration.

In Akka, rolling updates are typically used for a stateful Akka Cluster where you can't run two separate clusters in
parallel during the update, for example in blue green deployments.

For rolling updates related to Akka dependency version upgrades and the migration guides, please see
@ref:[Rolling Updates and Akka versions](../project/rolling-update.md)


### Serialization Compatibility

There are two parts of Akka that need careful consideration when performing an rolling update.

1. Compatibility of remote message protocols. Old nodes may send messages to new nodes and vice versa.
1. Serialization format of persisted events and snapshots. New nodes must be able to read old data, and
   during the update old nodes must be able to read data stored by new nodes.

There are many more application specific aspects. It's important to have a strategy for serialization
format that can be evolved and you can find advice in
@ref:[Persistence - Schema Evolution](../persistence-schema-evolution.md), which also applies to
remote messages when deploying with rolling updates.

When retiring a serializer without downtime, one approach is to do it in two phases: in the first phase, you deploy a version of your application that still supports deserializing messages in the old form (your deserializer is still in @akka.actor.serializers@, but it no longer serializes new messages (it is removed from @akka.actor.serialization-bindings@). Then after this upgrade has succeeded, and there are no longer systems sending messages serialized with the serializer that is being decommissioned, you can upgrade to a version that also no longer contains this serializer in @akka.actor.serializers@.

### Akka Cluster Configuration Compatibility Check

Relevant information on rolling updates and enforcing [Akka Cluster configuration compatibility checks](cluster-usage.md#configuration-compatibility-check)
on joining nodes.

### Akka Cluster Sharding

During a rolling upgrade, entities receiving traffic may be moved multiple times landing (on each shuffle) in an old or new node in the cluster, handled largely by the pluggable allocation strategy.

When an old node is stopped the shards running there may be allocated on one of the other old nodes remaining in the cluster. Later that node is stopped, and so on.

the cluster was restarted with more nodes and they didn’t increase the number of shards accordingly.
Shards per node is not fixed, they can end up out of balance as you add new nodes

### Akka Cluster Shutdown order

It's best to keep the node that has been running the longest until last. That is because
Cluster Singletons (including Cluster Sharding coordinators) are running on the oldest nodes. It's more
efficient to avoid moving the singletons more than necessary since those typically have to recover
their state and it might introduce unnecessary delays in for example access to new sharded actors.
