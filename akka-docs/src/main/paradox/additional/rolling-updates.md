# Rolling Updates

A rolling update is the process of replacing one version of the system with another without downtime.
The changes can be new code, changed dependencies such as new Akka version, or modified configuration.

In Akka, rolling updates are typically used for a stateful Akka Cluster where you can't run two separate clusters in
parallel during the update, for example in blue green deployments.

For rolling updates related to Akka dependency version upgrades and the migration guides, please see
@ref:[Rolling Updates and Akka versions](../project/rolling-update.md)

## Serialization Compatibility

There are two parts of Akka that need careful consideration when performing an rolling update.

1. Compatibility of remote message protocols. Old nodes may send messages to new nodes and vice versa.
1. Serialization format of persisted events and snapshots. New nodes must be able to read old data, and
   during the update old nodes must be able to read data stored by new nodes.

There are many more application specific aspects for serialization changes during rolling upgrades to consider. 
For example based on the use case and requirements, whether to allow dropped messages or tear down the TCP connection when the manifest is unknown.
When some message loss during a rolling upgrade is acceptable versus a full shutdown and restart, assuming the application recovers afterwards 
* If a `java.io.NotSerializableException` is thrown in `fromBinary` this is treated as a transient problem, the issue logged and the message is dropped
* If other exceptions are thrown it can be an indication of corrupt bytes from the underlying transport, and the connection is broken

For more zero-impact rolling upgrades, it is important to consider a strategy for serialization that can be evolved. 
One approach to retiring a serializer without downtime is described in @ref:[two rolling upgrade steps to switch to the new serializer](../serialization.md#rolling-upgrades). 
Additionally you can find advice on @ref:[Persistence - Schema Evolution](../persistence-schema-evolution.md) which also applies to remote messages when deploying with rolling updates.


## Cluster Sharding

During a rolling upgrade, sharded entities receiving traffic may be moved during @ref:[shard rebalancing](../cluster-sharding.md#shard-rebalancing), 
to an old or new node in the cluster, based on the pluggable allocation strategy and settings.
When an old node is stopped the shards that were running on it are moved to one of the
other old nodes remaining in the cluster. The `ShardCoordinator` is itself a cluster singleton. 
To minimize downtime of the shard coordinator, see the strategies about  @ref[ClusterSingleton](#cluster-singleton) rolling upgrades below.

Some changes to sharding configuration require @ref:[a full cluster restart](../cluster-sharding.md#rolling-upgrades).

## Cluster Singleton

Cluster singletons are always running on the oldest node. To avoid moving cluster singletons more than necessary during a rolling upgrade, 
it is recommended to upgrade the oldest node last. This way cluster singletons are only moved once during a full rolling upgrade. 
Otherwise, in the worst case cluster singletons may be migrated from node to node which requires coordination and initialization 
overhead several times.

## Cluster Shutdown
 
### Graceful shutdown 

For rolling updates it is best to leave the Cluster gracefully via @ref:[Coordinated Shutdown](../actors.md#coordinated-shutdown),
which will run automatically on SIGTERM, when the Cluster node sees itself as `Exiting`.
Environments such as Kubernetes send a SIGTERM, however if the JVM is wrapped with a script ensure that it forwards the signal.
@ref:[Graceful shutdown](../cluster-sharding.md#graceful-shutdown) of Cluster Singletons and Cluster Sharding similarly happen automatically.

### Ungraceful shutdown 

In case of network failures it may still be necessary to set the node's status to Down in order to complete the removal. 
@ref:[Cluster Downing](../cluster-usage.md#downing) details downing nodes and downing providers. 
[Split Brain Resolver](https://doc.akka.io/docs/akka-enhancements/current/split-brain-resolver.html) can be used to ensure 
the cluster continues to function during network partitions and node failures. For example
if there is an unreachability problem Split Brain Resolver would make a decision based on the configured downing strategy. 
  
## Cluster Configuration Compatibility Checks

During rolling updates the configuration from existing nodes should pass the Cluster configuration compatibility checks.
For example, it is possible to migrate Cluster Sharding from Classic to Typed Actors in a rolling update using a two step approach
as of Akka version `2.5.23`:

* Deploy with the new nodes set to `akka.cluster.configuration-compatibility-check.enforce-on-join = off`
and ensure all nodes are in this state
* Deploy again and with the new nodes set to `akka.cluster.configuration-compatibility-check.enforce-on-join = on`. 
  
Full documentation about enforcing these checks on joining nodes and optionally adding custom checks can be found in  
@ref:[Akka Cluster configuration compatibility checks](../cluster-usage.md#configuration-compatibility-check).
