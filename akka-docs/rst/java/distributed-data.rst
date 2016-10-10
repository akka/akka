
.. _distributed_data_java:

##################
 Distributed Data
##################

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

.. warning::

  This module is marked as **“experimental”** as of its introduction in Akka 2.4.0. We will continue to
  improve this API based on our users’ feedback, which implies that while we try to keep incompatible
  changes to a minimum the binary compatibility guarantee for maintenance releases does not apply to the
  contents of the ``akka.persistence`` package.
  
Using the Replicator
====================

The ``akka.cluster.ddata.Replicator`` actor provides the API for interacting with the data.
The ``Replicator`` actor must be started on each node in the cluster, or group of nodes tagged 
with a specific role. It communicates with other ``Replicator`` instances with the same path 
(without address) that are running on other nodes . For convenience it can be used with the
``akka.cluster.ddata.DistributedData`` extension.

Cluster members with status :ref:`WeaklyUp <weakly_up_java>`, if that feature is enabled,
will participate in Distributed Data. This means that the data will be replicated to the
:ref:`WeaklyUp <weakly_up_java>` nodes with the background gossip protocol. Note that it
will not participate in any actions where the consistency mode is to read/write from all
nodes or the majority of nodes. The :ref:`WeaklyUp <weakly_up_java>` node is not counted
as part of the cluster. So 3 nodes + 5 :ref:`WeaklyUp <weakly_up_java>` is essentially a
3 node cluster as far as consistent actions are concerned.

Below is an example of an actor that schedules tick messages to itself and for each tick 
adds or removes elements from a ``ORSet`` (observed-remove set). It also subscribes to
changes of this. 

.. includecode:: code/docs/ddata/DataBot.java#data-bot

.. _replicator_update_java:

Update
------

To modify and replicate a data value you send a ``Replicator.Update`` message to the local
``Replicator``.

The current data value for the ``key`` of the ``Update`` is passed as parameter to the ``modify``
function of the ``Update``. The function is supposed to return the new value of the data, which
will then be replicated according to the given consistency level.

The ``modify`` function is called by the ``Replicator`` actor and must therefore be a pure
function that only uses the data parameter and stable fields from enclosing scope. It must
for example not access ``sender()`` reference of an enclosing actor.

``Update`` is intended to only be sent from an actor running in same local ``ActorSystem`` as
 * the `Replicator`, because the `modify` function is typically not serializable.

You supply a write consistency level which has the following meaning:

* ``writeLocal`` the value will immediately only be written to the local replica,
  and later disseminated with gossip
* ``writeTo(n)`` the value will immediately be written to at least ``n`` replicas,
  including the local replica
* ``writeMajority`` the value will immediately be written to a majority of replicas, i.e.
  at least **N/2 + 1** replicas, where N is the number of nodes in the cluster
  (or cluster role group)
* ``writeAll`` the value will immediately be written to all nodes in the cluster
  (or all nodes in the cluster role group)
  
.. includecode:: code/docs/ddata/DistributedDataDocTest.java#update  

As reply of the ``Update`` a ``Replicator.UpdateSuccess`` is sent to the sender of the
``Update`` if the value was successfully replicated according to the supplied consistency
level within the supplied timeout. Otherwise a ``Replicator.UpdateFailure`` subclass is
sent back. Note that a ``Replicator.UpdateTimeout`` reply does not mean that the update completely failed
or was rolled back. It may still have been replicated to some nodes, and will eventually
be replicated to all nodes with the gossip protocol.

.. includecode:: code/docs/ddata/DistributedDataDocTest.java#update-response1

.. includecode:: code/docs/ddata/DistributedDataDocTest.java#update-response2

You will always see your own writes. For example if you send two ``Update`` messages
changing the value of the same ``key``, the ``modify`` function of the second message will
see the change that was performed by the first ``Update`` message.

In the ``Update`` message you can pass an optional request context, which the ``Replicator``
does not care about, but is included in the reply messages. This is a convenient
way to pass contextual information (e.g. original sender) without having to use ``ask``
or maintain local correlation data structures.

.. includecode:: code/docs/ddata/DistributedDataDocTest.java#update-request-context

.. _replicator_get_java:
 
Get
---

To retrieve the current value of a data you send ``Replicator.Get`` message to the
``Replicator``. You supply a consistency level which has the following meaning:

* ``readLocal`` the value will only be read from the local replica
* ``readFrom(n)`` the value will be read and merged from ``n`` replicas,
  including the local replica
* ``readMajority`` the value will be read and merged from a majority of replicas, i.e.
  at least **N/2 + 1** replicas, where N is the number of nodes in the cluster
  (or cluster role group)
* ``readAll`` the value will be read and merged from all nodes in the cluster
  (or all nodes in the cluster role group)


.. includecode:: code/docs/ddata/DistributedDataDocTest.java#get

As reply of the ``Get`` a ``Replicator.GetSuccess`` is sent to the sender of the
``Get`` if the value was successfully retrieved according to the supplied consistency
level within the supplied timeout. Otherwise a ``Replicator.GetFailure`` is sent.
If the key does not exist the reply will be ``Replicator.NotFound``.

.. includecode:: code/docs/ddata/DistributedDataDocTest.java#get-response1

.. includecode:: code/docs/ddata/DistributedDataDocTest.java#get-response2

You will always read your own writes. For example if you send a ``Update`` message
followed by a ``Get`` of the same ``key`` the ``Get`` will retrieve the change that was
performed by the preceding ``Update`` message. However, the order of the reply messages are
not defined, i.e. in the previous example you may receive the ``GetSuccess`` before
the ``UpdateSuccess``.

In the ``Get`` message you can pass an optional request context in the same way as for the
``Update`` message, described above. For example the original sender can be passed and replied
to after receiving and transforming ``GetSuccess``.

.. includecode:: code/docs/ddata/DistributedDataDocTest.java#get-request-context

Consistency
-----------

The consistency level that is supplied in the :ref:`replicator_update_java` and :ref:`replicator_get_java`
specifies per request how many replicas that must respond successfully to a write and read request.

For low latency reads you use ``ReadLocal`` with the risk of retrieving stale data, i.e. updates
from other nodes might not be visible yet. 

When using ``writeLocal`` the update is only written to the local replica and then disseminated
in the background with the gossip protocol, which can take few seconds to spread to all nodes.

``writeAll`` and ``readAll`` is the strongest consistency level, but also the slowest and with
lowest availability. For example, it is enough that one node is unavailable for a ``Get`` request
and you will not receive the value.

If consistency is important, you can ensure that a read always reflects the most recent
write by using the following formula::

    (nodes_written + nodes_read) > N 

where N is the total number of nodes in the cluster, or the number of nodes with the role that is
used for the ``Replicator``.

For example, in a 7 node cluster this these consistency properties are achieved by writing to 4 nodes
and reading from 4 nodes, or writing to 5 nodes and reading from 3 nodes.

By combining ``writeMajority`` and ``readMajority`` levels a read always reflects the most recent write.
The ``Replicator`` writes and reads to a majority of replicas, i.e. **N / 2 + 1**. For example,
in a 5 node cluster it writes to 3 nodes and reads from 3 nodes. In a 6 node cluster it writes 
to 4 nodes and reads from 4 nodes.

Here is an example of using ``writeMajority`` and ``readMajority``:

.. includecode:: ../../../akka-samples/akka-sample-distributed-data-java/src/main/java/sample/distributeddata/ShoppingCart.java#read-write-majority

.. includecode:: ../../../akka-samples/akka-sample-distributed-data-java/src/main/java/sample/distributeddata/ShoppingCart.java#get-cart

.. includecode:: ../../../akka-samples/akka-sample-distributed-data-java/src/main/java/sample/distributeddata/ShoppingCart.java#add-item

In some rare cases, when performing an ``Update`` it is needed to first try to fetch latest data from
other nodes. That can be done by first sending a ``Get`` with ``ReadMajority`` and then continue with
the ``Update`` when the ``GetSuccess``, ``GetFailure`` or ``NotFound`` reply is received. This might be
needed when you need to base a decision on latest information or when removing entries from ``ORSet`` 
or ``ORMap``. If an entry is added to an ``ORSet`` or ``ORMap`` from one node and removed from another
node the entry will only be removed if the added entry is visible on the node where the removal is
performed (hence the name observed-removed set).

The following example illustrates how to do that:

.. includecode:: ../../../akka-samples/akka-sample-distributed-data-java/src/main/java/sample/distributeddata/ShoppingCart.java#remove-item 

.. warning::

  *Caveat:* Even if you use ``writeMajority`` and ``readMajority`` there is small risk that you may
  read stale data if the cluster membership has changed between the ``Update`` and the ``Get``.
  For example, in cluster of 5 nodes when you ``Update`` and that change is written to 3 nodes: 
  n1, n2, n3. Then 2 more nodes are added and a ``Get`` request is reading from 4 nodes, which 
  happens to be n4, n5, n6, n7, i.e. the value on n1, n2, n3 is not seen in the response of the 
  ``Get`` request.
  
Subscribe
---------

You may also register interest in change notifications by sending ``Replicator.Subscribe``
message to the ``Replicator``. It will send ``Replicator.Changed`` messages to the registered
subscriber when the data for the subscribed key is updated. Subscribers will be notified
periodically with the configured ``notify-subscribers-interval``, and it is also possible to
send an explicit ``Replicator.FlushChanges`` message to the ``Replicator`` to notify the subscribers
immediately.

The subscriber is automatically removed if the subscriber is terminated. A subscriber can
also be deregistered with the ``Replicator.Unsubscribe`` message.

.. includecode:: code/docs/ddata/DistributedDataDocTest.java#subscribe

Delete
------

A data entry can be deleted by sending a ``Replicator.Delete`` message to the local
local ``Replicator``. As reply of the ``Delete`` a ``Replicator.DeleteSuccess`` is sent to
the sender of the ``Delete`` if the value was successfully deleted according to the supplied
consistency level within the supplied timeout. Otherwise a ``Replicator.ReplicationDeleteFailure``
is sent. Note that ``ReplicationDeleteFailure`` does not mean that the delete completely failed or
was rolled back. It may still have been replicated to some nodes, and may eventually be replicated
to all nodes.

A deleted key cannot be reused again, but it is still recommended to delete unused
data entries because that reduces the replication overhead when new nodes join the cluster.
Subsequent ``Delete``, ``Update`` and ``Get`` requests will be replied with ``Replicator.DataDeleted``.
Subscribers will receive ``Replicator.DataDeleted``.

.. includecode:: code/docs/ddata/DistributedDataDocTest.java#delete

.. warning::

  As deleted keys continue to be included in the stored data on each node as well as in gossip 
  messages, a continuous series of updates and deletes of top-level entities will result in 
  growing memory usage until an ActorSystem runs out of memory. To use Akka Distributed Data 
  where frequent adds and removes are required, you should use a fixed number of top-level data 
  types that support both updates and removals, for example ``ORMap`` or ``ORSet``.

Data Types
==========

The data types must be convergent (stateful) CRDTs and implement the ``ReplicatedData`` trait,
i.e. they provide a monotonic merge function and the state changes always converge.

You can use your own custom ``ReplicatedData`` types, and several types are provided
by this package, such as:

* Counters: ``GCounter``, ``PNCounter``
* Sets: ``GSet``, ``ORSet``
* Maps: ``ORMap``, ``ORMultiMap``, ``LWWMap``, ``PNCounterMap``
* Registers: ``LWWRegister``, ``Flag``

Counters
--------

``GCounter`` is a "grow only counter". It only supports increments, no decrements.

It works in a similar way as a vector clock. It keeps track of one counter per node and the total 
value is the sum of these counters. The ``merge`` is implemented by taking the maximum count for
each node.

If you need both increments and decrements you can use the ``PNCounter`` (positive/negative counter).

It is tracking the increments (P) separate from the decrements (N). Both P and N are represented
as two internal ``GCounter``. Merge is handled by merging the internal P and N counters.
The value of the counter is the value of the P counter minus the value of the N counter.

.. includecode:: code/docs/ddata/DistributedDataDocTest.java#pncounter

Several related counters can be managed in a map with the ``PNCounterMap`` data type.
When the counters are placed in a ``PNCounterMap`` as opposed to placing them as separate top level
values they are guaranteed to be replicated together as one unit, which is sometimes necessary for
related data.

.. includecode:: code/docs/ddata/DistributedDataDocTest.java#pncountermap

Sets
----

If you only need to add elements to a set and not remove elements the ``GSet`` (grow-only set) is
the data type to use. The elements can be any type of values that can be serialized.
Merge is simply the union of the two sets.

.. includecode:: code/docs/ddata/DistributedDataDocTest.java#gset

If you need add and remove operations you should use the ``ORSet`` (observed-remove set).
Elements can be added and removed any number of times. If an element is concurrently added and
removed, the add will win. You cannot remove an element that you have not seen.

The ``ORSet`` has a version vector that is incremented when an element is added to the set.
The version for the node that added the element is also tracked for each element in a so
called "birth dot". The version vector and the dots are used by the ``merge`` function to
track causality of the operations and resolve concurrent updates.

.. includecode:: code/docs/ddata/DistributedDataDocTest.java#orset

Maps
----

``ORMap`` (observed-remove map) is a map with ``String`` keys and the values are ``ReplicatedData``
types themselves. It supports add, remove and delete any number of times for a map entry.

If an entry is concurrently added and removed, the add will win. You cannot remove an entry that
you have not seen. This is the same semantics as for the ``ORSet``.

If an entry is concurrently updated to different values the values will be merged, hence the
requirement that the values must be ``ReplicatedData`` types.

It is rather inconvenient to use the ``ORMap`` directly since it does not expose specific types
of the values. The ``ORMap`` is intended as a low level tool for building more specific maps,
such as the following specialized maps.

``ORMultiMap`` (observed-remove multi-map) is a multi-map implementation that wraps an
``ORMap`` with an ``ORSet`` for the map's value.

``PNCounterMap`` (positive negative counter map) is a map of named counters. It is a specialized 
``ORMap`` with ``PNCounter`` values.

``LWWMap`` (last writer wins map) is a specialized ``ORMap`` with ``LWWRegister`` (last writer wins register)
values. 

.. includecode:: code/docs/ddata/DistributedDataDocTest.java#ormultimap

When a data entry is changed the full state of that entry is replicated to other nodes, i.e.
when you update a map the whole map is replicated. Therefore, instead of using one ``ORMap``
with 1000 elements it is more efficient to split that up in 10 top level ``ORMap`` entries 
with 100 elements each. Top level entries are replicated individually, which has the 
trade-off that different entries may not be replicated at the same time and you may see
inconsistencies between related entries. Separate top level entries cannot be updated atomically
together.

Note that ``LWWRegister`` and therefore ``LWWMap`` relies on synchronized clocks and should only be used
when the choice of value is not important for concurrent updates occurring within the clock skew. Read more
in the below section about ``LWWRegister``.

Flags and Registers
-------------------

``Flag`` is a data type for a boolean value that is initialized to ``false`` and can be switched
to ``true``. Thereafter it cannot be changed. ``true`` wins over ``false`` in merge.

.. includecode:: code/docs/ddata/DistributedDataDocTest.java#flag

``LWWRegister`` (last writer wins register) can hold any (serializable) value.

Merge of a ``LWWRegister`` takes the register with highest timestamp. Note that this
relies on synchronized clocks. `LWWRegister` should only be used when the choice of
value is not important for concurrent updates occurring within the clock skew.

Merge takes the register updated by the node with lowest address (``UniqueAddress`` is ordered)
if the timestamps are exactly the same.

.. includecode:: code/docs/ddata/DistributedDataDocTest.java#lwwregister

Instead of using timestamps based on ``System.currentTimeMillis()`` time it is possible to
use a timestamp value based on something else, for example an increasing version number
from a database record that is used for optimistic concurrency control.

.. includecode:: code/docs/ddata/DistributedDataDocTest.java#lwwregister-custom-clock

For first-write-wins semantics you can use the ``LWWRegister#reverseClock`` instead of the
``LWWRegister#defaultClock``.

The ``defaultClock`` is using max value of ``System.currentTimeMillis()`` and ``currentTimestamp + 1``.
This means that the timestamp is increased for changes on the same node that occurs within
the same millisecond. It also means that it is safe to use the ``LWWRegister`` without
synchronized clocks when there is only one active writer, e.g. a Cluster Singleton. Such a
single writer should then first read current value with ``ReadMajority`` (or more) before
changing and writing the value with ``WriteMajority`` (or more).

Custom Data Type
----------------

You can rather easily implement your own data types. The only requirement is that it implements
the ``mergeData`` function of the ``AbstractReplicatedData`` class.

A nice property of stateful CRDTs is that they typically compose nicely, i.e. you can combine several
smaller data types to build richer data structures. For example, the ``PNCounter`` is composed of
two internal ``GCounter`` instances to keep track of increments and decrements separately.

Here is s simple implementation of a custom ``TwoPhaseSet`` that is using two internal ``GSet`` types
to keep track of addition and removals.  A ``TwoPhaseSet`` is a set where an element may be added and
removed, but never added again thereafter.

.. includecode:: code/docs/ddata/japi/TwoPhaseSet.java#twophaseset

Data types should be immutable, i.e. "modifying" methods should return a new instance.

Serialization
^^^^^^^^^^^^^

The data types must be serializable with an :ref:`Akka Serializer <serialization-java>`.
It is highly recommended that you implement  efficient serialization with Protobuf or similar
for your custom data types. The built in data types are marked with ``ReplicatedDataSerialization``
and serialized with ``akka.cluster.ddata.protobuf.ReplicatedDataSerializer``.

Serialization of the data types are used in remote messages and also for creating message
digests (SHA-1) to detect changes. Therefore it is important that the serialization is efficient
and produce the same bytes for the same content. For example sets and maps should be sorted
deterministically in the serialization.

This is a protobuf representation of the above ``TwoPhaseSet``:

.. includecode:: ../../src/main/protobuf/TwoPhaseSetMessages.proto#twophaseset

The serializer for the ``TwoPhaseSet``:

.. includecode:: code/docs/ddata/japi/protobuf/TwoPhaseSetSerializer.java#serializer

Note that the elements of the sets are sorted so the SHA-1 digests are the same
for the same elements.

You register the serializer in configuration:
 
.. includecode:: ../scala/code/docs/ddata/DistributedDataDocSpec.scala#japi-serializer-config

Using compression can sometimes be a good idea to reduce the data size. Gzip compression is
provided by the ``akka.cluster.ddata.protobuf.SerializationSupport`` trait:

.. includecode:: code/docs/ddata/japi/protobuf/TwoPhaseSetSerializerWithCompression.java#compression
 
The two embedded ``GSet`` can be serialized as illustrated above, but in general when composing
new data types from the existing built in types it is better to make use of the existing 
serializer for those types. This can be done by declaring those as bytes fields in protobuf:

.. includecode:: ../../src/main/protobuf/TwoPhaseSetMessages.proto#twophaseset2

and use the methods ``otherMessageToProto`` and ``otherMessageFromBinary`` that are provided
by the ``SerializationSupport`` trait to serialize and deserialize the ``GSet`` instances. This
works with any type that has a registered Akka serializer. This is how such an serializer would
look like for the ``TwoPhaseSet``:

.. includecode:: code/docs/ddata/japi/protobuf/TwoPhaseSetSerializer2.java#serializer
  
Durable Storage
---------------

By default the data is only kept in memory. It is redundant since it is replicated to other nodes 
in the cluster, but if you stop all nodes the data is lost, unless you have saved it 
elsewhere. 

Entries can be configured to be durable, i.e. stored on local disk on each node. The stored data will be loaded
next time the replicator is started, i.e. when actor system is restarted. This means data will survive as 
long as at least one node from the old cluster takes part in a new cluster. The keys of the durable entries
are configured with::

  akka.cluster.distributed-data.durable.keys = ["a", "b", "durable*"]

Prefix matching is supported by using ``*`` at the end of a key.

All entries can be made durable by specifying::

  akka.cluster.distributed-data.durable.keys = ["*"]

`LMDB <https://github.com/lmdbjava/lmdbjava/>`_ is the default storage implementation. It is 
possible to replace that with another implementation by implementing the actor protocol described in 
``akka.cluster.ddata.DurableStore`` and defining the ``akka.cluster.distributed-data.durable.store-actor-class``
property for the new implementation. 

The location of the files for the data is configured with::

  # Directory of LMDB file. There are two options:
  # 1. A relative or absolute path to a directory that ends with 'ddata'
  #    the full name of the directory will contain name of the ActorSystem
  #    and its remote port.
  # 2. Otherwise the path is used as is, as a relative or absolute path to
  #    a directory.
  akka.cluster.distributed-data.lmdb.dir = "ddata"

Making the data durable has of course a performance cost. By default, each update is flushed
to disk before the ``UpdateSuccess`` reply is sent. For better performance, but with the risk of losing 
the last writes if the JVM crashes, you can enable write behind mode. Changes are then accumulated during
a time period before it is written to LMDB and flushed to disk. Enabling write behind is especially
efficient when performing many writes to the same key, because it is only the last value for each key 
that will be serialized and stored. The risk of losing writes if the JVM crashes is small since the 
data is typically replicated to other nodes immediately according to the given ``WriteConsistency``.

::

  akka.cluster.distributed-data.lmdb.write-behind-interval = 200 ms

Note that you should be prepared to receive ``WriteFailure`` as reply to an ``Update`` of a 
durable entry if the data could not be stored for some reason. When enabling ``write-behind-interval``
such errors will only be logged and ``UpdateSuccess`` will still be the reply to the ``Update``.

CRDT Garbage
------------

One thing that can be problematic with CRDTs is that some data types accumulate history (garbage).
For example a ``GCounter`` keeps track of one counter per node. If a ``GCounter`` has been updated
from one node it will associate the identifier of that node forever. That can become a problem
for long running systems with many cluster nodes being added and removed. To solve this problem
the ``Replicator`` performs pruning of data associated with nodes that have been removed from the
cluster. Data types that need pruning have to implement the ``RemovedNodePruning`` trait. 

Samples
=======

Several interesting samples are included and described in the `Lightbend Activator <http://www.lightbend.com/platform/getstarted>`_
tutorial named `Akka Distributed Data Samples with Java <http://www.lightbend.com/activator/template/akka-sample-distributed-data-java>`_.

* Low Latency Voting Service
* Highly Available Shopping Cart
* Distributed Service Registry
* Replicated Cache
* Replicated Metrics 

Limitations
===========

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

When a data entry is changed the full state of that entry is replicated to other nodes. For example,
if you add one element to a Set with 100 existing elements, all 101 elements are transferred to
other nodes. This means that you cannot have too large data entries, because then the remote message
size will be too large. We might be able to make this more efficient by implementing
`Efficient State-based CRDTs by Delta-Mutation <http://gsd.di.uminho.pt/members/cbm/ps/delta-crdt-draft16may2014.pdf>`_.

Learn More about CRDTs
======================

* `The Final Causal Frontier <http://www.ustream.tv/recorded/61448875>`_
  talk by Sean Cribbs
* `Eventually Consistent Data Structures <https://vimeo.com/43903960>`_
  talk by Sean Cribbs
* `Strong Eventual Consistency and Conflict-free Replicated Data Types <http://research.microsoft.com/apps/video/default.aspx?id=153540&r=1>`_
  talk by Mark Shapiro
* `A comprehensive study of Convergent and Commutative Replicated Data Types <http://hal.upmc.fr/file/index/docid/555588/filename/techreport.pdf>`_
  paper by Mark Shapiro et. al.

Dependencies
------------

To use Distributed Data you must add the following dependency in your project.

sbt::

    "com.typesafe.akka" %% "akka-distributed-data-experimental" % "@version@" @crossString@

maven::

  <dependency>
    <groupId>com.typesafe.akka</groupId>
    <artifactId>akka-distributed-data-experimental_@binVersion@</artifactId>
    <version>@version@</version>
  </dependency>

Configuration
=============
  
The ``DistributedData`` extension can be configured with the following properties:

.. includecode:: ../../../akka-distributed-data/src/main/resources/reference.conf#distributed-data
 