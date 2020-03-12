# Sharded Daemon Process


@@@ warning

This module is currently marked as @ref:[may change](../common/may-change.md) because it is a new feature that
needs feedback from real usage before finalizing the API. This means that API or semantics can change without
warning or deprecation period. It is also not recommended to use this module in production just yet.

@@@

## Module info

To use Akka Sharded Daemon Process, you must add the following dependency in your project:

@@dependency[sbt,Maven,Gradle] {
  group=com.typesafe.akka
  artifact=akka-cluster-sharding-typed_$scala.binary_version$
  version=$akka.version$
}

@@project-info{ projectId="akka-cluster-sharding-typed" }

## Introduction

Sharded Daemon Process provides a way to run `N` actors, each given a numeric id starting from `0` that are then kept alive
and balanced across the cluster. When a rebalance is needed the actor is stopped and, triggered by a keep alive running on 
all nodes, started on a new node (the keep alive should be seen as an implementation detail and may change in future versions).

The intended use case is for splitting data processing workloads across a set number of workers that each get to work on a subset
of the data that needs to be processed. This is commonly needed to create projections based on the event streams available
from all the @ref:[EventSourcedBehaviors](persistence.md) in a CQRS application. Events are tagged with one out of `N` tags
used to split the workload of consuming and updating a projection between `N` workers.

For cases where a single actor needs to be kept alive see @ref:[Cluster Singleton](cluster-singleton.md)

## Basic example

To set up a set of actors running with Sharded Daemon process each node in the cluster needs to run the same initialization
when starting up:

Scala
:  @@snip [ShardedDaemonProcessExample.scala](/akka-cluster-sharding-typed/src/test/scala/akka/cluster/sharding/typed/scaladsl/ShardedDaemonProcessSpec.scala) { #tag-processing }

Java
:  @@snip [ShardedDaemonProcessExample.java](/akka-cluster-sharding-typed/src/test/java/akka/cluster/sharding/typed/javadsl/ShardedDaemonProcessCompileOnlyTest.java) { #tag-processing }

An additional factory method is provided for further configurability and providing a graceful stop message for the actor.

## Adressing the actors

In use cases where you need to send messages to the daemon process actors it is recommended to use the @ref:[system receptionist](actor-discovery.md)
either with a single `ServiceKey` which all daemon process actors register themeselves to for broadcasts or individual keys if more fine grained messaging is needed.

## Scalability  

This cluster tool is intended for small numbers of consumers and will not scale well to a large set. In large clusters 
it is recommended to limit the nodes the sharded daemon process will run on using a role.