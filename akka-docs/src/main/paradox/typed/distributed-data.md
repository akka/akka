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

The @scala[`akka.typed.cluster.ddata.scaladsl.Replicator`]@java[`akka.typed.cluster.ddata.javadsl.Replicator`] 
actor provides the API for interacting with the data and is accessed through the extension 
@scala[`akka.typed.cluster.ddata.scaladsl.DistributedData`]@java[`akka.typed.cluster.ddata.javadsl.DistributedData`].

Note that the messages for the replicator, such as `Replicator.Update` are defined in @scala[`akka.typed.cluster.ddata.scaladsl.Replicator`]
@java[`akka.typed.cluster.ddata.scaladsl.Replicator`] but the actual CRDTs are the 
same as in untyped, for example `akka.cluster.ddata.GCounter`.

Something something untyped cluster in scope/passed in


This sample uses the replicated datatype `GCounter` to implement a counter that can be written to on any node of the
cluster: 

Scala
:  @@snip [DistributedDataExampleSpec.scala](/akka-cluster-typed/src/test/scala/docs/akka/cluster/typed/DistributedDataExampleSpec.scala) { #sample }

Java
:  @@snip [DistributedDataExampleTest.java](/akka-cluster-typed/src/test/java/jdocs/akka/cluster/typed/DistributedDataExampleTest.java) { #sample }



### Running separate instances of the replicator

For some use cases (FIXME which?) it makes sense to start separate replicators, this needs to be done on all nodes, or 
the group of nodes tagged with a specific role. To do this with the Typed Distributed Data you will first
have to start an untyped `Replicator` and pass it to the `Replicator.behavior` method that takes an untyped
actor ref. All such `Replicator`s must run on the same path in the untyped actor hierarchy.
 

TODO https://github.com/akka/akka/issues/24494

See [https://akka.io/blog/2017/10/04/typed-cluster-tools](https://akka.io/blog/2017/10/04/typed-cluster-tools)
