# Distributed Data

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

The `akka.cluster.ddata.Replicator` actor provides the API for interacting with the data.
The `Replicator` actor must be started on each node in the cluster, or group of nodes tagged
with a specific role. It communicates with other `Replicator` instances with the same path
(without address) that are running on other nodes . For convenience it can be used with the
`akka.cluster.ddata.DistributedData` extension but it can also be started as an ordinary
actor using the `Replicator.props`. If it is started as an ordinary actor it is important
that it is given the same name, started on same path, on all nodes.

Cluster members with status @ref:[WeaklyUp](cluster-usage.md#weakly-up),
will participate in Distributed Data. This means that the data will be replicated to the
@ref:[WeaklyUp](cluster-usage.md#weakly-up) nodes with the background gossip protocol. Note that it
will not participate in any actions where the consistency mode is to read/write from all
nodes or the majority of nodes. The @ref:[WeaklyUp](cluster-usage.md#weakly-up) node is not counted
as part of the cluster. So 3 nodes + 5 @ref:[WeaklyUp](cluster-usage.md#weakly-up) is essentially a
3 node cluster as far as consistent actions are concerned.

Below is an example of an actor that schedules tick messages to itself and for each tick
adds or removes elements from a `ORSet` (observed-remove set). It also subscribes to
changes of this.

Scala
: @@snip [DistributedDataDocSpec.scala]($code$/scala/docs/ddata/DistributedDataDocSpec.scala) { #data-bot }

Java
: @@snip [DataBot.java]($code$/java/jdocs/ddata/DataBot.java) { #data-bot }

<a id="replicator-update"></a>
### Update

To modify and replicate a data value you send a `Replicator.Update` message to the local
`Replicator`.

The current data value for the `key` of the `Update` is passed as parameter to the `modify`
function of the `Update`. The function is supposed to return the new value of the data, which
will then be replicated according to the given consistency level.

The `modify` function is called by the `Replicator` actor and must therefore be a pure
function that only uses the data parameter and stable fields from enclosing scope. It must
for example not access the sender (@scala[`sender()`]@java[`getSender()`]) reference of an enclosing actor.

`Update`
 is intended to only be sent from an actor running in same local 
`ActorSystem`
 as
: the `Replicator`, because the `modify` function is typically not serializable.


You supply a write consistency level which has the following meaning:

 * @scala[`WriteLocal`]@java[`writeLocal`] the value will immediately only be written to the local replica,
and later disseminated with gossip
 * `WriteTo(n)` the value will immediately be written to at least `n` replicas,
including the local replica
 * `WriteMajority` the value will immediately be written to a majority of replicas, i.e.
at least **N/2 + 1** replicas, where N is the number of nodes in the cluster
(or cluster role group)
 * `WriteAll` the value will immediately be written to all nodes in the cluster
(or all nodes in the cluster role group)

When you specify to write to `n` out of `x`  nodes, the update will first replicate to `n` nodes. 
If there are not enough Acks after 1/5th of the timeout, the update will be replicated to `n` other 
nodes. If there are less than n nodes left all of the remaining nodes are used. Reachable nodes 
are prefered over unreachable nodes.

Note that `WriteMajority` has a `minCap` parameter that is useful to specify to achieve better safety for small clusters.

Scala
: @@snip [DistributedDataDocSpec.scala]($code$/scala/docs/ddata/DistributedDataDocSpec.scala) { #update }

Java
: @@snip [DistributedDataDocTest.java]($code$/java/jdocs/ddata/DistributedDataDocTest.java) { #update }

As reply of the `Update` a `Replicator.UpdateSuccess` is sent to the sender of the
`Update` if the value was successfully replicated according to the supplied consistency
level within the supplied timeout. Otherwise a `Replicator.UpdateFailure` subclass is
sent back. Note that a `Replicator.UpdateTimeout` reply does not mean that the update completely failed
or was rolled back. It may still have been replicated to some nodes, and will eventually
be replicated to all nodes with the gossip protocol.

Scala
: @@snip [DistributedDataDocSpec.scala]($code$/scala/docs/ddata/DistributedDataDocSpec.scala) { #update-response1 }

Java
: @@snip [DistributedDataDocTest.java]($code$/java/jdocs/ddata/DistributedDataDocTest.java) { #update-response1 }


Scala
: @@snip [DistributedDataDocSpec.scala]($code$/scala/docs/ddata/DistributedDataDocSpec.scala) { #update-response2 }

Java
: @@snip [DistributedDataDocTest.java]($code$/java/jdocs/ddata/DistributedDataDocTest.java) { #update-response2 }

You will always see your own writes. For example if you send two `Update` messages
changing the value of the same `key`, the `modify` function of the second message will
see the change that was performed by the first `Update` message.

In the `Update` message you can pass an optional request context, which the `Replicator`
does not care about, but is included in the reply messages. This is a convenient
way to pass contextual information (e.g. original sender) without having to use `ask`
or maintain local correlation data structures.

Scala
: @@snip [DistributedDataDocSpec.scala]($code$/scala/docs/ddata/DistributedDataDocSpec.scala) { #update-request-context }

Java
: @@snip [DistributedDataDocTest.java]($code$/java/jdocs/ddata/DistributedDataDocTest.java) { #update-request-context }

<a id="replicator-get"></a>
### Get

To retrieve the current value of a data you send `Replicator.Get` message to the
`Replicator`. You supply a consistency level which has the following meaning:

 * @scala[`ReadLocal`]@java[`readLocal`] the value will only be read from the local replica
 * `ReadFrom(n)` the value will be read and merged from `n` replicas,
including the local replica
 * `ReadMajority` the value will be read and merged from a majority of replicas, i.e.
at least **N/2 + 1** replicas, where N is the number of nodes in the cluster
(or cluster role group)
 * `ReadAll` the value will be read and merged from all nodes in the cluster
(or all nodes in the cluster role group)

Note that `ReadMajority` has a `minCap` parameter that is useful to specify to achieve better safety for small clusters.

Scala
: @@snip [DistributedDataDocSpec.scala]($code$/scala/docs/ddata/DistributedDataDocSpec.scala) { #get }

Java
: @@snip [DistributedDataDocTest.java]($code$/java/jdocs/ddata/DistributedDataDocTest.java) { #get }

As reply of the `Get` a `Replicator.GetSuccess` is sent to the sender of the
`Get` if the value was successfully retrieved according to the supplied consistency
level within the supplied timeout. Otherwise a `Replicator.GetFailure` is sent.
If the key does not exist the reply will be `Replicator.NotFound`.

Scala
: @@snip [DistributedDataDocSpec.scala]($code$/scala/docs/ddata/DistributedDataDocSpec.scala) { #get-response1 }

Java
: @@snip [DistributedDataDocTest.java]($code$/java/jdocs/ddata/DistributedDataDocTest.java) { #get-response1 }


Scala
: @@snip [DistributedDataDocSpec.scala]($code$/scala/docs/ddata/DistributedDataDocSpec.scala) { #get-response2 }

Java
: @@snip [DistributedDataDocTest.java]($code$/java/jdocs/ddata/DistributedDataDocTest.java) { #get-response2 }

You will always read your own writes. For example if you send a `Update` message
followed by a `Get` of the same `key` the `Get` will retrieve the change that was
performed by the preceding `Update` message. However, the order of the reply messages are
not defined, i.e. in the previous example you may receive the `GetSuccess` before
the `UpdateSuccess`.

In the `Get` message you can pass an optional request context in the same way as for the
`Update` message, described above. For example the original sender can be passed and replied
to after receiving and transforming `GetSuccess`.

Scala
: @@snip [DistributedDataDocSpec.scala]($code$/scala/docs/ddata/DistributedDataDocSpec.scala) { #get-request-context }

Java
: @@snip [DistributedDataDocTest.java]($code$/java/jdocs/ddata/DistributedDataDocTest.java) { #get-request-context }

### Consistency

The consistency level that is supplied in the [Update](#replicator-update) and [Get](#replicator-get)
specifies per request how many replicas that must respond successfully to a write and read request.

For low latency reads you use @scala[`ReadLocal`]@java[`readLocal`] with the risk of retrieving stale data, i.e. updates
from other nodes might not be visible yet.

When using @scala[`WriteLocal`]@java[`writeLocal`] the update is only written to the local replica and then disseminated
in the background with the gossip protocol, which can take few seconds to spread to all nodes.

`WriteAll` and `ReadAll` is the strongest consistency level, but also the slowest and with
lowest availability. For example, it is enough that one node is unavailable for a `Get` request
and you will not receive the value.

If consistency is important, you can ensure that a read always reflects the most recent
write by using the following formula:

```
(nodes_written + nodes_read) > N
```

where N is the total number of nodes in the cluster, or the number of nodes with the role that is
used for the `Replicator`.

For example, in a 7 node cluster this these consistency properties are achieved by writing to 4 nodes
and reading from 4 nodes, or writing to 5 nodes and reading from 3 nodes.

By combining `WriteMajority` and `ReadMajority` levels a read always reflects the most recent write.
The `Replicator` writes and reads to a majority of replicas, i.e. **N / 2 + 1**. For example,
in a 5 node cluster it writes to 3 nodes and reads from 3 nodes. In a 6 node cluster it writes
to 4 nodes and reads from 4 nodes.

You can define a minimum number of nodes for `WriteMajority` and `ReadMajority`,
this will minimize the risk of reading steal data. Minimum cap is
provided by minCap property of `WriteMajority` and `ReadMajority` and defines the required majority.
If the minCap is higher then **N / 2 + 1** the minCap will be used.

For example if the minCap is 5 the `WriteMajority` and `ReadMajority` for cluster of 3 nodes will be 3, for
cluster of 6 nodes will be 5 and for cluster of 12 nodes will be 7 ( **N / 2 + 1** ).

For small clusters (<7) the risk of membership changes between a WriteMajority and ReadMajority
is rather high and then the nice properties of combining majority write and reads are not
guaranteed. Therefore the `ReadMajority` and `WriteMajority` have a `minCap` parameter that
is useful to specify to achieve better safety for small clusters. It means that if the cluster
size is smaller than the majority size it will use the `minCap` number of nodes but at most
the total size of the cluster.

Here is an example of using `WriteMajority` and `ReadMajority`:

Scala
: @@snip [ShoppingCart.scala]($code$/scala/docs/ddata/ShoppingCart.scala) { #read-write-majority }

Java
: @@snip [ShoppingCart.java]($code$/java/jdocs/ddata/ShoppingCart.java) { #read-write-majority }


Scala
: @@snip [ShoppingCart.scala]($code$/scala/docs/ddata/ShoppingCart.scala) { #get-cart }

Java
: @@snip [ShoppingCart.java]($code$/java/jdocs/ddata/ShoppingCart.java) { #get-cart }


Scala
: @@snip [ShoppingCart.scala]($code$/scala/docs/ddata/ShoppingCart.scala) { #add-item }

Java
: @@snip [ShoppingCart.java]($code$/java/jdocs/ddata/ShoppingCart.java) { #add-item }

In some rare cases, when performing an `Update` it is needed to first try to fetch latest data from
other nodes. That can be done by first sending a `Get` with `ReadMajority` and then continue with
the `Update` when the `GetSuccess`, `GetFailure` or `NotFound` reply is received. This might be
needed when you need to base a decision on latest information or when removing entries from `ORSet`
or `ORMap`. If an entry is added to an `ORSet` or `ORMap` from one node and removed from another
node the entry will only be removed if the added entry is visible on the node where the removal is
performed (hence the name observed-removed set).

The following example illustrates how to do that:

Scala
: @@snip [ShoppingCart.scala]($code$/scala/docs/ddata/ShoppingCart.scala) { #remove-item }

Java
: @@snip [ShoppingCart.java]($code$/java/jdocs/ddata/ShoppingCart.java) { #remove-item }

@@@ warning

*Caveat:* Even if you use `WriteMajority` and `ReadMajority` there is small risk that you may
read stale data if the cluster membership has changed between the `Update` and the `Get`.
For example, in cluster of 5 nodes when you `Update` and that change is written to 3 nodes:
n1, n2, n3. Then 2 more nodes are added and a `Get` request is reading from 4 nodes, which
happens to be n4, n5, n6, n7, i.e. the value on n1, n2, n3 is not seen in the response of the
`Get` request.

@@@

### Subscribe

You may also register interest in change notifications by sending `Replicator.Subscribe`
message to the `Replicator`. It will send `Replicator.Changed` messages to the registered
subscriber when the data for the subscribed key is updated. Subscribers will be notified
periodically with the configured `notify-subscribers-interval`, and it is also possible to
send an explicit `Replicator.FlushChanges` message to the `Replicator` to notify the subscribers
immediately.

The subscriber is automatically removed if the subscriber is terminated. A subscriber can
also be deregistered with the `Replicator.Unsubscribe` message.

Scala
: @@snip [DistributedDataDocSpec.scala]($code$/scala/docs/ddata/DistributedDataDocSpec.scala) { #subscribe }

Java
: @@snip [DistributedDataDocTest.java]($code$/java/jdocs/ddata/DistributedDataDocTest.java) { #subscribe }

### Delete

A data entry can be deleted by sending a `Replicator.Delete` message to the local
local `Replicator`. As reply of the `Delete` a `Replicator.DeleteSuccess` is sent to
the sender of the `Delete` if the value was successfully deleted according to the supplied
consistency level within the supplied timeout. Otherwise a `Replicator.ReplicationDeleteFailure`
is sent. Note that `ReplicationDeleteFailure` does not mean that the delete completely failed or
was rolled back. It may still have been replicated to some nodes, and may eventually be replicated
to all nodes.

A deleted key cannot be reused again, but it is still recommended to delete unused
data entries because that reduces the replication overhead when new nodes join the cluster.
Subsequent `Delete`, `Update` and `Get` requests will be replied with `Replicator.DataDeleted`.
Subscribers will receive `Replicator.Deleted`.

In the *Delete* message you can pass an optional request context in the same way as for the
*Update* message, described above. For example the original sender can be passed and replied
to after receiving and transforming *DeleteSuccess*.

Scala
: @@snip [DistributedDataDocSpec.scala]($code$/scala/docs/ddata/DistributedDataDocSpec.scala) { #delete }

Java
: @@snip [DistributedDataDocTest.java]($code$/java/jdocs/ddata/DistributedDataDocTest.java) { #delete }

@@@ warning

As deleted keys continue to be included in the stored data on each node as well as in gossip
messages, a continuous series of updates and deletes of top-level entities will result in
growing memory usage until an ActorSystem runs out of memory. To use Akka Distributed Data
where frequent adds and removes are required, you should use a fixed number of top-level data
types that support both updates and removals, for example `ORMap` or `ORSet`.

@@@

<a id="delta-crdt"></a>
### delta-CRDT

[Delta State Replicated Data Types](http://arxiv.org/abs/1603.01529)
are supported. delta-CRDT is a way to reduce the need for sending the full state
for updates. For example adding element `'c'` and `'d'` to set `{'a', 'b'}` would
result in sending the delta `{'c', 'd'}` and merge that with the state on the
receiving side, resulting in set `{'a', 'b', 'c', 'd'}`.

The protocol for replicating the deltas supports causal consistency if the data type
is marked with `RequiresCausalDeliveryOfDeltas`. Otherwise it is only eventually
consistent. Without causal consistency it means that if elements `'c'` and `'d'` are
added in two separate *Update* operations these deltas may occasionally be propagated
to nodes in different order than the causal order of the updates. For this example it
can result in that set `{'a', 'b', 'd'}` can be seen before element 'c' is seen. Eventually
it will be `{'a', 'b', 'c', 'd'}`.

Note that the full state is occasionally also replicated for delta-CRDTs, for example when
new nodes are added to the cluster or when deltas could not be propagated because
of network partitions or similar problems.

The the delta propagation can be disabled with configuration property:

```
akka.cluster.distributed-data.delta-crdt.enabled=off
```

## Data Types

The data types must be convergent (stateful) CRDTs and implement the @scala[`ReplicatedData` trait]@java[`AbstractReplicatedData` interface],
i.e. they provide a monotonic merge function and the state changes always converge.

You can use your own custom @scala[`ReplicatedData` or `DeltaReplicatedData`]@java[`AbstractReplicatedData` or `AbstractDeltaReplicatedData`] types, and several types are provided
by this package, such as:

 * Counters: `GCounter`, `PNCounter`
 * Sets: `GSet`, `ORSet`
 * Maps: `ORMap`, `ORMultiMap`, `LWWMap`, `PNCounterMap`
 * Registers: `LWWRegister`, `Flag`

### Counters

`GCounter` is a "grow only counter". It only supports increments, no decrements.

It works in a similar way as a vector clock. It keeps track of one counter per node and the total
value is the sum of these counters. The `merge` is implemented by taking the maximum count for
each node.

If you need both increments and decrements you can use the `PNCounter` (positive/negative counter).

It is tracking the increments (P) separate from the decrements (N). Both P and N are represented
as two internal `GCounter`. Merge is handled by merging the internal P and N counters.
The value of the counter is the value of the P counter minus the value of the N counter.

Scala
: @@snip [DistributedDataDocSpec.scala]($code$/scala/docs/ddata/DistributedDataDocSpec.scala) { #pncounter }

Java
: @@snip [DistributedDataDocTest.java]($code$/java/jdocs/ddata/DistributedDataDocTest.java) { #pncounter }

`GCounter` and `PNCounter` have support for [delta-CRDT](#delta-crdt) and don't need causal
delivery of deltas.

Several related counters can be managed in a map with the `PNCounterMap` data type.
When the counters are placed in a `PNCounterMap` as opposed to placing them as separate top level
values they are guaranteed to be replicated together as one unit, which is sometimes necessary for
related data.

Scala
: @@snip [DistributedDataDocSpec.scala]($code$/scala/docs/ddata/DistributedDataDocSpec.scala) { #pncountermap }

Java
: @@snip [DistributedDataDocTest.java]($code$/java/jdocs/ddata/DistributedDataDocTest.java) { #pncountermap }

### Sets

If you only need to add elements to a set and not remove elements the `GSet` (grow-only set) is
the data type to use. The elements can be any type of values that can be serialized.
Merge is simply the union of the two sets.

Scala
: @@snip [DistributedDataDocSpec.scala]($code$/scala/docs/ddata/DistributedDataDocSpec.scala) { #gset }

Java
: @@snip [DistributedDataDocTest.java]($code$/java/jdocs/ddata/DistributedDataDocTest.java) { #gset }

`GSet` has support for [delta-CRDT](#delta-crdt) and it doesn't require causal delivery of deltas.

If you need add and remove operations you should use the `ORSet` (observed-remove set).
Elements can be added and removed any number of times. If an element is concurrently added and
removed, the add will win. You cannot remove an element that you have not seen.

The `ORSet` has a version vector that is incremented when an element is added to the set.
The version for the node that added the element is also tracked for each element in a so
called "birth dot". The version vector and the dots are used by the `merge` function to
track causality of the operations and resolve concurrent updates.

Scala
: @@snip [DistributedDataDocSpec.scala]($code$/scala/docs/ddata/DistributedDataDocSpec.scala) { #orset }

Java
: @@snip [DistributedDataDocTest.java]($code$/java/jdocs/ddata/DistributedDataDocTest.java) { #orset }

`ORSet` has support for [delta-CRDT](#delta-crdt) and it requires causal delivery of deltas.

### Maps

`ORMap` (observed-remove map) is a map with keys of `Any` type and the values are `ReplicatedData`
types themselves. It supports add, remove and delete any number of times for a map entry.

If an entry is concurrently added and removed, the add will win. You cannot remove an entry that
you have not seen. This is the same semantics as for the `ORSet`.

If an entry is concurrently updated to different values the values will be merged, hence the
requirement that the values must be `ReplicatedData` types.

It is rather inconvenient to use the `ORMap` directly since it does not expose specific types
of the values. The `ORMap` is intended as a low level tool for building more specific maps,
such as the following specialized maps.

`ORMultiMap` (observed-remove multi-map) is a multi-map implementation that wraps an
`ORMap` with an `ORSet` for the map's value.

`PNCounterMap` (positive negative counter map) is a map of named counters (where the name can be of any type).
It is a specialized `ORMap` with `PNCounter` values.

`LWWMap` (last writer wins map) is a specialized `ORMap` with `LWWRegister` (last writer wins register)
values.

`ORMap`, `ORMultiMap`, `PNCounterMap` and `LWWMap` have support for [delta-CRDT](#delta-crdt) and they require causal
delivery of deltas. Support for deltas here means that the `ORSet` being underlying key type for all those maps
uses delta propagation to deliver updates. Effectively, the update for map is then a pair, consisting of delta for the `ORSet`
being the key and full update for the respective value (`ORSet`, `PNCounter` or `LWWRegister`) kept in the map.

There is a special version of `ORMultiMap`, created by using separate constructor
`ORMultiMap.emptyWithValueDeltas[A, B]`, that also propagates the updates to its values (of `ORSet` type) as deltas.
This means that the `ORMultiMap` initiated with `ORMultiMap.emptyWithValueDeltas` propagates its updates as pairs
consisting of delta of the key and delta of the value. It is much more efficient in terms of network bandwith consumed.
However, this behaviour has not been made default for `ORMultiMap` because currently the merge process for
updates for `ORMultiMap.emptyWithValueDeltas` results in a tombstone (being a form of [CRDT Garbage](#crdt-garbage) )
in form of additional `ORSet` entry being created in a situation when a key has been added and then removed.
There is ongoing work aimed at removing necessity of creation of the aforementioned tombstone. Please also note
that despite having the same Scala type, `ORMultiMap.emptyWithValueDeltas` is not compatible with 'vanilla' `ORMultiMap`,
because of different replication mechanism.

Scala
: @@snip [DistributedDataDocSpec.scala]($code$/scala/docs/ddata/DistributedDataDocSpec.scala) { #ormultimap }

Java
: @@snip [DistributedDataDocTest.java]($code$/java/jdocs/ddata/DistributedDataDocTest.java) { #ormultimap }

When a data entry is changed the full state of that entry is replicated to other nodes, i.e.
when you update a map the whole map is replicated. Therefore, instead of using one `ORMap`
with 1000 elements it is more efficient to split that up in 10 top level `ORMap` entries
with 100 elements each. Top level entries are replicated individually, which has the
trade-off that different entries may not be replicated at the same time and you may see
inconsistencies between related entries. Separate top level entries cannot be updated atomically
together.

Note that `LWWRegister` and therefore `LWWMap` relies on synchronized clocks and should only be used
when the choice of value is not important for concurrent updates occurring within the clock skew. Read more
in the below section about `LWWRegister`.

### Flags and Registers

`Flag` is a data type for a boolean value that is initialized to `false` and can be switched
to `true`. Thereafter it cannot be changed. `true` wins over `false` in merge.

Scala
: @@snip [DistributedDataDocSpec.scala]($code$/scala/docs/ddata/DistributedDataDocSpec.scala) { #flag }

Java
: @@snip [DistributedDataDocTest.java]($code$/java/jdocs/ddata/DistributedDataDocTest.java) { #flag }

`LWWRegister` (last writer wins register) can hold any (serializable) value.

Merge of a `LWWRegister` takes the register with highest timestamp. Note that this
relies on synchronized clocks. *LWWRegister* should only be used when the choice of
value is not important for concurrent updates occurring within the clock skew.

Merge takes the register updated by the node with lowest address (`UniqueAddress` is ordered)
if the timestamps are exactly the same.

Scala
: @@snip [DistributedDataDocSpec.scala]($code$/scala/docs/ddata/DistributedDataDocSpec.scala) { #lwwregister }

Java
: @@snip [DistributedDataDocTest.java]($code$/java/jdocs/ddata/DistributedDataDocTest.java) { #lwwregister }

Instead of using timestamps based on `System.currentTimeMillis()` time it is possible to
use a timestamp value based on something else, for example an increasing version number
from a database record that is used for optimistic concurrency control.

Scala
: @@snip [DistributedDataDocSpec.scala]($code$/scala/docs/ddata/DistributedDataDocSpec.scala) { #lwwregister-custom-clock }

Java
: @@snip [DistributedDataDocTest.java]($code$/java/jdocs/ddata/DistributedDataDocTest.java) { #lwwregister-custom-clock }

For first-write-wins semantics you can use the `LWWRegister#reverseClock` instead of the
`LWWRegister#defaultClock`.

The `defaultClock` is using max value of `System.currentTimeMillis()` and `currentTimestamp + 1`.
This means that the timestamp is increased for changes on the same node that occurs within
the same millisecond. It also means that it is safe to use the `LWWRegister` without
synchronized clocks when there is only one active writer, e.g. a Cluster Singleton. Such a
single writer should then first read current value with `ReadMajority` (or more) before
changing and writing the value with `WriteMajority` (or more).

### Custom Data Type

You can rather easily implement your own data types. The only requirement is that it implements
the @scala[`merge`]@java[`mergeData`] function of the @scala[`ReplicatedData`]@java[`AbstractReplicatedData`] trait.

A nice property of stateful CRDTs is that they typically compose nicely, i.e. you can combine several
smaller data types to build richer data structures. For example, the `PNCounter` is composed of
two internal `GCounter` instances to keep track of increments and decrements separately.

Here is s simple implementation of a custom `TwoPhaseSet` that is using two internal `GSet` types
to keep track of addition and removals.  A `TwoPhaseSet` is a set where an element may be added and
removed, but never added again thereafter.

Scala
: @@snip [TwoPhaseSet.scala]($code$/scala/docs/ddata/TwoPhaseSet.scala) { #twophaseset }

Java
: @@snip [TwoPhaseSet.java]($code$/java/jdocs/ddata/TwoPhaseSet.java) { #twophaseset }

Data types should be immutable, i.e. "modifying" methods should return a new instance.

Implement the additional methods of @scala[`DeltaReplicatedData`]@java[`AbstractDeltaReplicatedData`] if it has support for delta-CRDT replication.

#### Serialization

The data types must be serializable with an @ref:[Akka Serializer](serialization.md).
It is highly recommended that you implement  efficient serialization with Protobuf or similar
for your custom data types. The built in data types are marked with `ReplicatedDataSerialization`
and serialized with `akka.cluster.ddata.protobuf.ReplicatedDataSerializer`.

Serialization of the data types are used in remote messages and also for creating message
digests (SHA-1) to detect changes. Therefore it is important that the serialization is efficient
and produce the same bytes for the same content. For example sets and maps should be sorted
deterministically in the serialization.

This is a protobuf representation of the above `TwoPhaseSet`:

@@snip [TwoPhaseSetMessages.proto]($code$/../main/protobuf/TwoPhaseSetMessages.proto) { #twophaseset }

The serializer for the `TwoPhaseSet`:

Scala
: @@snip [TwoPhaseSetSerializer.scala]($code$/scala/docs/ddata/protobuf/TwoPhaseSetSerializer.scala) { #serializer }

Java
: @@snip [TwoPhaseSetSerializer.java]($code$/java/jdocs/ddata/protobuf/TwoPhaseSetSerializer.java) { #serializer }

Note that the elements of the sets are sorted so the SHA-1 digests are the same
for the same elements.

You register the serializer in configuration:

Scala
: @@snip [DistributedDataDocSpec.scala]($code$/scala/docs/ddata/DistributedDataDocSpec.scala) { #serializer-config }

Java
: @@snip [DistributedDataDocSpec.scala]($code$/scala/docs/ddata/DistributedDataDocSpec.scala) { #japi-serializer-config }

Using compression can sometimes be a good idea to reduce the data size. Gzip compression is
provided by the @scala[`akka.cluster.ddata.protobuf.SerializationSupport` trait]@java[`akka.cluster.ddata.protobuf.AbstractSerializationSupport` interface]:

Scala
: @@snip [TwoPhaseSetSerializer.scala]($code$/scala/docs/ddata/protobuf/TwoPhaseSetSerializer.scala) { #compression }

Java
: @@snip [TwoPhaseSetSerializerWithCompression.java]($code$/java/jdocs/ddata/protobuf/TwoPhaseSetSerializerWithCompression.java) { #compression }

The two embedded `GSet` can be serialized as illustrated above, but in general when composing
new data types from the existing built in types it is better to make use of the existing
serializer for those types. This can be done by declaring those as bytes fields in protobuf:

@@snip [TwoPhaseSetMessages.proto]($code$/../main/protobuf/TwoPhaseSetMessages.proto) { #twophaseset2 }

and use the methods `otherMessageToProto` and `otherMessageFromBinary` that are provided
by the `SerializationSupport` trait to serialize and deserialize the `GSet` instances. This
works with any type that has a registered Akka serializer. This is how such an serializer would
look like for the `TwoPhaseSet`:

Scala
: @@snip [TwoPhaseSetSerializer2.scala]($code$/scala/docs/ddata/protobuf/TwoPhaseSetSerializer2.scala) { #serializer }

Java
: @@snip [TwoPhaseSetSerializer2.java]($code$/java/jdocs/ddata/protobuf/TwoPhaseSetSerializer2.java) { #serializer }

<a id="ddata-durable"></a>
### Durable Storage

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

Making the data durable has of course a performance cost. By default, each update is flushed
to disk before the `UpdateSuccess` reply is sent. For better performance, but with the risk of losing
the last writes if the JVM crashes, you can enable write behind mode. Changes are then accumulated during
a time period before it is written to LMDB and flushed to disk. Enabling write behind is especially
efficient when performing many writes to the same key, because it is only the last value for each key
that will be serialized and stored. The risk of losing writes if the JVM crashes is small since the
data is typically replicated to other nodes immediately according to the given `WriteConsistency`.

```
akka.cluster.distributed-data.lmdb.write-behind-interval = 200 ms
```

Note that you should be prepared to receive `WriteFailure` as reply to an `Update` of a
durable entry if the data could not be stored for some reason. When enabling `write-behind-interval`
such errors will only be logged and `UpdateSuccess` will still be the reply to the `Update`.

There is one important caveat when it comes pruning of [CRDT Garbage](#crdt-garbage) for durable data.
If and old data entry that was never pruned is injected and merged with existing data after
that the pruning markers have been removed the value will not be correct. The time-to-live
of the markers is defined by configuration
`akka.cluster.distributed-data.durable.remove-pruning-marker-after` and is in the magnitude of days.
This would be possible if a node with durable data didn't participate in the pruning
(e.g. it was shutdown) and later started after this time. A node with durable data should not
be stopped for longer time than this duration and if it is joining again after this
duration its data should first be manually removed (from the lmdb directory).

<a id="crdt-garbage"></a>
### CRDT Garbage

One thing that can be problematic with CRDTs is that some data types accumulate history (garbage).
For example a `GCounter` keeps track of one counter per node. If a `GCounter` has been updated
from one node it will associate the identifier of that node forever. That can become a problem
for long running systems with many cluster nodes being added and removed. To solve this problem
the `Replicator` performs pruning of data associated with nodes that have been removed from the
cluster. Data types that need pruning have to implement the `RemovedNodePruning` trait. See the
API documentation of the `Replicator` for details.

## Samples

Several interesting samples are included and described in the
tutorial named @scala[@extref[Akka Distributed Data Samples with Scala](ecs:akka-samples-distributed-data-scala) (@extref[source code](samples:akka-sample-distributed-data-scala))]@java[@extref[Akka Distributed Data Samples with Java](ecs:akka-samples-distributed-data-java) (@extref[source code](samples:akka-sample-distributed-data-java))]

 * Low Latency Voting Service
 * Highly Available Shopping Cart
 * Distributed Service Registry
 * Replicated Cache
 * Replicated Metrics

## Limitations

There are some limitations that you should be aware of.

CRDTs cannot be used for all types of problems, and eventual consistency does not fit
all domains. Sometimes you need strong consistency.

It is not intended for *Big Data*. The number of top level entries should not exceed 100000.
When a new node is added to the cluster all these entries are transferred (gossiped) to the
new node. The entries are split up in chunks and all existing nodes collaborate in the gossip,
but it will take a while (tens of seconds) to transfer all entries and this means that you
cannot have too many top level entries. The current recommended limit is 100000. We will
be able to improve this if needed, but the design is still not intended for billions of entries.

All data is held in memory, which is another reason why it is not intended for *Big Data*.

When a data entry is changed the full state of that entry may be replicated to other nodes
if it doesn't support [delta-CRDT](#delta-crdt). The full state is also replicated for delta-CRDTs,
for example when new nodes are added to the cluster or when deltas could not be propagated because
of network partitions or similar problems. This means that you cannot have too large
data entries, because then the remote message size will be too large.

## Learn More about CRDTs

 * [The Final Causal Frontier](http://www.ustream.tv/recorded/61448875)
talk by Sean Cribbs
 * [Eventually Consistent Data Structures](https://vimeo.com/43903960)
talk by Sean Cribbs
 * [Strong Eventual Consistency and Conflict-free Replicated Data Types](http://research.microsoft.com/apps/video/default.aspx?id=153540&r=1)
talk by Mark Shapiro
 * [A comprehensive study of Convergent and Commutative Replicated Data Types](http://hal.upmc.fr/file/index/docid/555588/filename/techreport.pdf)
paper by Mark Shapiro et. al.

## Dependencies

To use Distributed Data you must add the following dependency in your project.

sbt
:   @@@vars
    ```
    "com.typesafe.akka" %% "akka-distributed-data" % "$akka.version$"
    ```
    @@@

Maven
:   @@@vars
    ```
    <dependency>
      <groupId>com.typesafe.akka</groupId>
      <artifactId>akka-distributed-data_$scala.binary_version$</artifactId>
      <version>$akka.version$</version>
    </dependency>
    ```
    @@@

## Configuration

The `DistributedData` extension can be configured with the following properties:

@@snip [reference.conf]($akka$/akka-distributed-data/src/main/resources/reference.conf) { #distributed-data }
