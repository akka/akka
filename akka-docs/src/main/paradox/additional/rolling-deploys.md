# Rolling Deploys

## Rolling Updates

A rolling update is the process of replacing one version of the system with another without downtime.
The changes can be new code, changed dependencies such as new Akka version, or modified configuration.

In Akka, rolling updates are typically used for a stateful Akka Cluster where you can't run two separate clusters in
parallel during the update, for example in blue green deployments.

### Compatibility of remote message protocols and serialization

There are two parts of Akka that need careful consideration when performing an rolling update.

1. Compatibility of remote message protocols. Old nodes may send messages to new nodes and vice versa.
1. Serialization format of persisted events and snapshots. New nodes must be able to read old data, and
   during the update old nodes must be able to read data stored by new nodes.

There are many more application specific aspects. It's important to have a strategy for serialization
format that can be evolved and you can find advice in
@ref:[Persistence - Schema Evolution](../persistence-schema-evolution.md), which also applies to
remote messages when deploying with rolling updates.


For rolling updates related to Akka dependency version upgrades and the migration guides, please see
@ref:[Rolling Updates and Akka versions](../project/rolling-update.md)


### Akka Cluster Configuration Compatibility Check

Relevant information on rolling updates and enforcing [Akka Cluster configuration compatibility checks](cluster-usage.md#configuration-compatibility-check)
on joining nodes.

### Akka Cluster Shutdown order

It's best to keep the node that has been running the longest until last. That is because
Cluster Singletons (including Cluster Sharding coordinators) are running on the oldest nodes. It's more
efficient to avoid moving the singletons more than necessary since those typically have to recover
their state and it might introduce unnecessary delays in for example access to new sharded actors.
