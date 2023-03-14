# Sharded Daemon Process

## Module info

To use Akka Sharded Daemon Process, you must add the following dependency in your project:

@@dependency[sbt,Maven,Gradle] {
  bomGroup=com.typesafe.akka bomArtifact=akka-bom_$scala.binary.version$ bomVersionSymbols=AkkaVersion
  symbol1=AkkaVersion
  value1="$akka.version$"
  group=com.typesafe.akka
  artifact=akka-cluster-sharding-typed_$scala.binary.version$
  version=AkkaVersion
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

## Addressing the actors

In use cases where you need to send messages to the daemon process actors it is recommended to use the @ref:[system receptionist](actor-discovery.md)
either with a single `ServiceKey` which all daemon process actors register themeselves to for broadcasts or individual keys if more fine grained messaging is needed.

## Dynamic scaling of number of workers

Starting the sharded daemon process with `initWithContext` returns an `ActorRef[ShardedDaemonProcessCommand]` that accepts a @apidoc[ChangeNumberOfProcesses] command to rescale the process to a new number of workers.

The rescaling process among other things includes the process actors stopping themselves in response to a stop message 
so may be a relatively slow operation. If a subsequent request to rescale is sent while one is in progress it is responded
to with a failure response.

A rolling upgrade switching from a static number of workers to a dynamic number is possible unless the
sharded daemon process was limited to a role. With roles the same process may end up running parallel instances until the 
rolling upgrade has completed. It is not safe to roll from dynamic to static.

## Scalability  

This cluster tool is intended for small numbers of consumers and will not scale well to a large set. In large clusters 
it is recommended to limit the nodes the sharded daemon process will run on using a role.

## Configuration

The following configuration properties are read by the @apidoc[ShardedDaemonProcessSettings]
when created with a @apidoc[ActorSystem](typed.ActorSystem) parameter:

@@snip [reference.conf](/akka-cluster-sharding-typed/src/main/resources/reference.conf) { #sharded-daemon-process }
