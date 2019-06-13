# Distributed Data

## Dependency

To use Akka Cluster Distributed Data Typed, you must add the following dependency in your project:

@@dependency[sbt,Maven,Gradle] {
  group=com.typesafe.akka
  artifact=akka-cluster-typed_$scala.binary_version$
  version=$akka.version$
}

## Introduction

*Akka Distributed Data* is useful when you need to share data between nodes in an
Akka Cluster. The data is accessed with an actor providing a key-value store like API.
The keys are unique identifiers with type information of the data values. The values
are *Conflict Free Replicated Data Types* (CRDTs).

All data entries are spread to all nodes, or nodes with a certain role, in the cluster
via direct replication and gossip based dissemination. You have fine grained control
of the consistency level for reads and writes.

The nature CRDTs makes it possible to perform updates from any node without coordination.
Concurrent updates from different nodes will automatically be resolved by the monotonic
merge function, which all data types must provide. The state changes always converge.
Several useful data types for counters, sets, maps and registers are provided and
you can also implement your own custom data types.

It is eventually consistent and geared toward providing high read and write availability
(partition tolerance), with low latency. Note that in an eventually consistent system a read may return an
out-of-date value.

## Using the Replicator

The @scala[@apidoc[akka.cluster.ddata.typed.scaladsl.Replicator]]@java[@apidoc[akka.cluster.ddata.typed.javadsl.Replicator]] 
actor provides the API for interacting with the data and is accessed through the extension 
@scala[@apidoc[akka.cluster.ddata.typed.scaladsl.DistributedData]]@java[@apidoc[akka.cluster.ddata.typed.javadsl.DistributedData]].

The messages for the replicator, such as `Replicator.Update` are defined in @scala[`akka.cluster.ddata.typed.scaladsl.Replicator`]
@java[`akka.cluster.ddata.typed.scaladsl.Replicator`] but the actual CRDTs are the 
same as in untyped, for example `akka.cluster.ddata.GCounter`. This will require a @scala[implicit] `akka.cluster.ddata.SelfUniqueAddress.SelfUniqueAddress`,
available from @scala[`implicit val node = DistributedData(system).selfUniqueAddress`]@java[SelfUniqueAddress node = DistributedData.get(system).selfUniqueAddress();].

The replicator can contain multiple entries each containing a replicated data type, we therefore need to create a 
key identifying the entry and helping us know what type it has, and then use that key for every interaction with
the replicator. Each replicated data type contains a factory for defining such a key.

This sample uses the replicated data type `GCounter` to implement a counter that can be written to on any node of the
cluster: 

Scala
:  @@snip [ReplicatorSpec.scala](/akka-cluster-typed/src/test/scala/akka/cluster/ddata/typed/scaladsl/ReplicatorSpec.scala) { #sample }

Java
:  @@snip [ReplicatorTest.java](/akka-cluster-typed/src/test/java/akka/cluster/ddata/typed/javadsl/ReplicatorTest.java) { #sample }


When we start up the actor we subscribe it to changes for our key, meaning whenever the replicator observes a change
for the counter our actor will receive a @scala[`Replicator.Changed[GCounter]`]@java[`Replicator.Changed<GCounter>`]. Since
this is not a message in our protocol, we use an adapter to wrap it in the internal `InternalChanged` message, which
is then handled in the regular message handling of the behavior. 

For an incoming `Increment` command, we send the `replicator` a `Replicator.Update` request, it contains five values:

 1. the @scala[`Key`]@java[`KEY`] we want to update
 1. the data to use if as the empty state if the replicator has not seen the key before
 1. the consistency level we want for the update
 1. an @scala[`ActorRef[Replicator.UpdateResponse[GCounter]]`]@java[`ActorRef<Replicator.UpdateResponse<GCounter>>`] 
    to respond to when the update is completed
 1. a function that takes a previous state and updates it, in our case by incrementing it with 1

Whenever the distributed counter is updated, we cache the value so that we can answer requests about the value without
the extra interaction with the replicator using the `GetCachedValue` command.

The example also supports asking the replicator using the `GetValue` command. Note how the `replyTo` from the
incoming message can be used when the `GetSuccess` response from the replicator is received.

See the @ref[the untyped Distributed Data documentation](../distributed-data.md#using-the-replicator)
for more details about `Get`, `Update` and `Delete` interactions with the replicator.

### Replicated data types

Akka contains a set of useful replicated data types and it is fully possible to implement custom replicated data types. 
For more details, read @ref[the untyped Distributed Data documentation](../distributed-data.md#data-types)

### Running separate instances of the replicator

For some use cases, for example when limiting the replicator to certain roles, or using different subsets on different roles,
it makes sense to start separate replicators, this needs to be done on all nodes, or 
the group of nodes tagged with a specific role. To do this with the Typed Distributed Data you will first
have to start an untyped `Replicator` and pass it to the `Replicator.behavior` method that takes an untyped
actor ref. All such `Replicator`s must run on the same path in the untyped actor hierarchy.
 
