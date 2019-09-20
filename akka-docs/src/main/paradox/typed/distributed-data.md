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

The @apidoc[typed.*.Replicator]
actor provides the API for interacting with the data and is accessed through the extension 
@apidoc[typed.*.DistributedData].

The messages for the replicator, such as `Replicator.Update` are defined in @apidoc[typed.*.Replicator]
but the actual CRDTs are the 
same as in classic, for example `akka.cluster.ddata.GCounter`. This will require a @scala[implicit] `akka.cluster.ddata.SelfUniqueAddress.SelfUniqueAddress`,
available from @scala[`implicit val node = DistributedData(system).selfUniqueAddress`]@java[SelfUniqueAddress node = DistributedData.get(system).selfUniqueAddress();].

The replicator can contain multiple entries each containing a replicated data type, we therefore need to create a 
key identifying the entry and helping us know what type it has, and then use that key for every interaction with
the replicator. Each replicated data type contains a factory for defining such a key.

This sample uses the replicated data type `GCounter` to implement a counter that can be written to on any node of the
cluster: 

Scala
:  @@snip [ReplicatorSpec.scala](/akka-cluster-typed/src/test/scala/docs/akka/cluster/ddata/typed/scaladsl/ReplicatorDocSpec.scala) { #sample }

Java
:  @@snip [ReplicatorTest.java](/akka-cluster-typed/src/test/java/jdocs/akka/cluster/ddata/typed/javadsl/ReplicatorDocSample.java) { #sample }

Although you can interact with the `Replicator` using the @scala[`ActorRef[Replicator.Command]`]@java[`ActorRef<Replicator.Command>`]
from @scala[`DistributedData(ctx.system).replicator`]@java[`DistributedData(ctx.getSystem()).replicator()`] it's
often more convenient to use the `ReplicatorMessageAdapter` as in the above example.

When we start up the actor we subscribe it to changes for our key, meaning whenever the replicator observes a change
for the counter our actor will receive a @scala[`Replicator.Changed[GCounter]`]@java[`Replicator.Changed<GCounter>`]. Since
this is not a message in our protocol, we use a message transformation function to wrap it in the internal `InternalChanged`
message, which is then handled in the regular message handling of the behavior.

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

See the @ref:[the classic Distributed Data documentation](../distributed-data.md#using-the-replicator)
for more details about `Get`, `Update` and `Delete` interactions with the replicator.

@@@ div { .group-scala }
There is alternative way of constructing the function for the `Update` message:

Scala
:  @@snip [ReplicatorSpec.scala](/akka-cluster-typed/src/test/scala/akka/cluster/ddata/typed/scaladsl/ReplicatorCompileOnlyTest.scala) { #curried-update }

Similar is supported for `Get` and `Delete`:

Scala
:  @@snip [ReplicatorSpec.scala](/akka-cluster-typed/src/test/scala/akka/cluster/ddata/typed/scaladsl/ReplicatorCompileOnlyTest.scala) { #curried-get }

@@@

### Replicated data types

Akka contains a set of useful replicated data types and it is fully possible to implement custom replicated data types. 
For more details, read @ref:[the classic Distributed Data documentation](../distributed-data.md#data-types)

### Running separate instances of the replicator

For some use cases, for example when limiting the replicator to certain roles, or using different subsets on different roles,
it makes sense to start separate replicators, this needs to be done on all nodes, or 
the group of nodes tagged with a specific role. To do this with Distributed Data you will first
have to start a classic `Replicator` and pass it to the `Replicator.behavior` method that takes a classic
actor ref. All such `Replicator`s must run on the same path in the classic actor hierarchy.

A standalone `ReplicatorMessageAdapter` can also be created for a given `Replicator` instead of creating
one via the `DistributedData` extension.

## Durable Storage

By default the data is only kept in memory. It is redundant since it is replicated to other nodes
in the cluster, but if you stop all nodes the data is lost, unless you have saved it
elsewhere.

Entries can be configured to be durable, i.e. stored on local disk on each node. The stored data will be loaded
next time the replicator is started, i.e. when actor system is restarted. This means data will survive as
long as at least one node from the old cluster takes part in a new cluster. The keys of the durable entries
are configured with:

```
akka.cluster.distributed-data.durable.keys = ["a", "b", "durable*"]
```

Prefix matching is supported by using `*` at the end of a key.

All entries can be made durable by specifying:

```
akka.cluster.distributed-data.durable.keys = ["*"]
```

@scala[[LMDB](https://symas.com/products/lightning-memory-mapped-database/)]@java[[LMDB](https://github.com/lmdbjava/lmdbjava/)] is the default storage implementation. It is
possible to replace that with another implementation by implementing the actor protocol described in
`akka.cluster.ddata.DurableStore` and defining the `akka.cluster.distributed-data.durable.store-actor-class`
property for the new implementation.

The location of the files for the data is configured with:

Scala
:   ```
# Directory of LMDB file. There are two options:
# 1. A relative or absolute path to a directory that ends with 'ddata'
#    the full name of the directory will contain name of the ActorSystem
#    and its remote port.
# 2. Otherwise the path is used as is, as a relative or absolute path to
#    a directory.
akka.cluster.distributed-data.durable.lmdb.dir = "ddata"
```

Java
:   ```
# Directory of LMDB file. There are two options:
# 1. A relative or absolute path to a directory that ends with 'ddata'
#    the full name of the directory will contain name of the ActorSystem
#    and its remote port.
# 2. Otherwise the path is used as is, as a relative or absolute path to
#    a directory.
akka.cluster.distributed-data.durable.lmdb.dir = "ddata"
```


When running in production you may want to configure the directory to a specific
path (alt 2), since the default directory contains the remote port of the
actor system to make the name unique. If using a dynamically assigned
port (0) it will be different each time and the previously stored data
will not be loaded.

Making the data durable has a performance cost. By default, each update is flushed
to disk before the `UpdateSuccess` reply is sent. For better performance, but with the risk of losing
the last writes if the JVM crashes, you can enable write behind mode. Changes are then accumulated during
a time period before it is written to LMDB and flushed to disk. Enabling write behind is especially
efficient when performing many writes to the same key, because it is only the last value for each key
that will be serialized and stored. The risk of losing writes if the JVM crashes is small since the
data is typically replicated to other nodes immediately according to the given `WriteConsistency`.

```
akka.cluster.distributed-data.durable.lmdb.write-behind-interval = 200 ms
```

Note that you should be prepared to receive `WriteFailure` as reply to an `Update` of a
durable entry if the data could not be stored for some reason. When enabling `write-behind-interval`
such errors will only be logged and `UpdateSuccess` will still be the reply to the `Update`.

There is one important caveat when it comes pruning of [CRDT Garbage](#crdt-garbage) for durable data.
If an old data entry that was never pruned is injected and merged with existing data after
that the pruning markers have been removed the value will not be correct. The time-to-live
of the markers is defined by configuration
`akka.cluster.distributed-data.durable.remove-pruning-marker-after` and is in the magnitude of days.
This would be possible if a node with durable data didn't participate in the pruning
(e.g. it was shutdown) and later started after this time. A node with durable data should not
be stopped for longer time than this duration and if it is joining again after this
duration its data should first be manually removed (from the lmdb directory).