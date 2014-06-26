.. _cluster-sharding:

Cluster Sharding
================

Cluster sharding is useful when you need to distribute actors across several nodes in the cluster and want to
be able to interact with them using their logical identifier, but without having to care about
their physical location in the cluster, which might also change over time.

It could for example be actors representing Aggregate Roots in Domain-Driven Design terminology.
Here we call these actors "entries". These actors typically have persistent (durable) state, 
but this feature is not limited to actors with persistent state.

Cluster sharding is typically used when you have many stateful actors that together consume
more resources (e.g. memory) than fit on one machine. If you only have a few stateful actors
it might be easier to run them on a :ref:`cluster-singleton` node. 

In this context sharding means that actors with an identifier, so called entries,
can be automatically distributed across multiple nodes in the cluster. Each entry
actor runs only at one place, and messages can be sent to the entry without requiring
the sender() to know the location of the destination actor. This is achieved by sending
the messages via a ``ShardRegion`` actor provided by this extension, which knows how
to route the message with the entry id to the final destination.

An Example in Java
------------------

This is how an entry actor may look like:

.. includecode:: @contribSrc@/src/test/java/akka/contrib/pattern/ClusterShardingTest.java#counter-actor

The above actor uses event sourcing and the support provided in ``UntypedPersistentActor`` to store its state.
It does not have to be a persistent actor, but in case of failure or migration of entries between nodes it must be able to recover
its state if it is valuable.

Note how the ``persistenceId`` is defined. You may define it another way, but it must be unique.

When using the sharding extension you are first, typically at system startup on each node
in the cluster, supposed to register the supported entry types with the ``ClusterSharding.start``
method. ``ClusterSharding.start`` gives you the reference which you can pass along.

.. includecode:: @contribSrc@/src/test/java/akka/contrib/pattern/ClusterShardingTest.java#counter-start

The ``messageExtractor`` defines application specific methods to extract the entry
identifier and the shard identifier from incoming messages.

.. includecode:: @contribSrc@/src/test/java/akka/contrib/pattern/ClusterShardingTest.java#counter-extractor

This example illustrates two different ways to define the entry identifier in the messages: 

 * The ``Get`` message includes the identifier itself.
 * The ``EntryEnvelope`` holds the identifier, and the actual message that is
   sent to the entry actor is wrapped in the envelope. 

Note how these two messages types are handled in the ``entryId`` and ``entryMessage`` methods shown above.

A shard is a group of entries that will be managed together. The grouping is defined by the
``shardResolver`` function shown above. Creating a good sharding algorithm is an interesting challenge
in itself. Try to produce a uniform distribution, i.e. same amount of entries in each shard.
As a rule of thumb, the number of shards should be a factor ten greater than the planned maximum number 
of cluster nodes.

Messages to the entries are always sent via the local ``ShardRegion``. The ``ShardRegion`` actor for a 
named entry type can be retrieved with ``ClusterSharding.shardRegion``. The ``ShardRegion`` will
lookup the location of the shard for the entry if it does not already know its location. It will
delegate the message to the right node and it will create the entry actor on demand, i.e. when the
first message for a specific entry is delivered.

.. includecode:: @contribSrc@/src/test/java/akka/contrib/pattern/ClusterShardingTest.java#counter-usage

An Example in Scala
-------------------

This is how an entry actor may look like:

.. includecode:: @contribSrc@/src/multi-jvm/scala/akka/contrib/pattern/ClusterShardingSpec.scala#counter-actor

The above actor uses event sourcing and the support provided in ``PersistentActor`` to store its state.
It does not have to be a persistent actor, but in case of failure or migration of entries between nodes it must be able to recover
its state if it is valuable.

Note how the ``persistenceId`` is defined. You may define it another way, but it must be unique.

When using the sharding extension you are first, typically at system startup on each node
in the cluster, supposed to register the supported entry types with the ``ClusterSharding.start``
method. ``ClusterSharding.start`` gives you the reference which you can pass along.

.. includecode:: @contribSrc@/src/multi-jvm/scala/akka/contrib/pattern/ClusterShardingSpec.scala#counter-start

The ``idExtractor`` and ``shardResolver`` are two application specific functions to extract the entry
identifier and the shard identifier from incoming messages.

.. includecode:: @contribSrc@/src/multi-jvm/scala/akka/contrib/pattern/ClusterShardingSpec.scala#counter-extractor

This example illustrates two different ways to define the entry identifier in the messages: 

 * The ``Get`` message includes the identifier itself.
 * The ``EntryEnvelope`` holds the identifier, and the actual message that is
   sent to the entry actor is wrapped in the envelope. 

Note how these two messages types are handled in the ``idExtractor`` function shown above.

A shard is a group of entries that will be managed together. The grouping is defined by the
``shardResolver`` function shown above. Creating a good sharding algorithm is an interesting challenge
in itself. Try to produce a uniform distribution, i.e. same amount of entries in each shard.
As a rule of thumb, the number of shards should be a factor ten greater than the planned maximum number 
of cluster nodes.   

Messages to the entries are always sent via the local ``ShardRegion``. The ``ShardRegion`` actor for a 
named entry type can be retrieved with ``ClusterSharding.shardRegion``. The ``ShardRegion`` will
lookup the location of the shard for the entry if it does not already know its location. It will
delegate the message to the right node and it will create the entry actor on demand, i.e. when the
first message for a specific entry is delivered.

.. includecode:: @contribSrc@/src/multi-jvm/scala/akka/contrib/pattern/ClusterShardingSpec.scala#counter-usage

A more comprehensive sample is available in the `Typesafe Activator <http://www.typesafe.com/platform/getstarted>`_
tutorial named `Akka Cluster Sharding with Scala! <http://www.typesafe.com/activator/template/akka-cluster-sharding-scala>`_.

How it works
------------

The ``ShardRegion`` actor is started on each node in the cluster, or group of nodes
tagged with a specific role. The ``ShardRegion`` is created with two application specific
functions to extract the entry identifier and the shard identifier from incoming messages.
A shard is a group of entries that will be managed together. For the first message in a 
specific shard the ``ShardRegion`` request the location of the shard from a central coordinator,
the ``ShardCoordinator``. 

The ``ShardCoordinator`` decides which ``ShardRegion`` that
owns the shard. The ``ShardRegion`` receives the decided home of the shard
and if that is the ``ShardRegion`` instance itself it will create a local child
actor representing the entry and direct all messages for that entry to it.
If the shard home is another ``ShardRegion`` instance messages will be forwarded
to that ``ShardRegion`` instance instead. While resolving the location of a
shard incoming messages for that shard are buffered and later delivered when the
shard home is known. Subsequent messages to the resolved shard can be delivered
to the target destination immediately without involving the ``ShardCoordinator``.

Scenario 1:

#. Incoming message M1 to ``ShardRegion`` instance R1. 
#. M1 is mapped to shard S1. R1 doesn't know about S1, so it asks the coordinator C for the location of S1. 
#. C answers that the home of S1 is R1.
#. R1 creates child actor for the entry E1 and sends buffered messages for S1 to E1 child
#. All incoming messages for S1 which arrive at R1 can be handled by R1 without C. It creates entry children as needed, and forwards messages to them.

Scenario 2:

#. Incoming message M2 to R1. 
#. M2 is mapped to S2. R1 doesn't know about S2, so it asks C for the location of S2. 
#. C answers that the home of S2 is R2.
#. R1 sends buffered messages for S2 to R2
#. All incoming messages for S2 which arrive at R1 can be handled by R1 without C. It forwards messages to R2.
#. R2 receives message for S2, ask C, which answers that the home of S2 is R2, and we are in Scenario 1 (but for R2). 

To make sure that at most one instance of a specific entry actor is running somewhere
in the cluster it is important that all nodes have the same view of where the shards
are located. Therefore the shard allocation decisions are taken by the central
``ShardCoordinator``, which is running as a cluster singleton, i.e. one instance on
the oldest member among all cluster nodes or a group of nodes tagged with a specific
role.

The logic that decides where a shard is to be located is defined in a pluggable shard
allocation strategy. The default implementation ``ShardCoordinator.LeastShardAllocationStrategy``
allocates new shards to the ``ShardRegion`` with least number of previously allocated shards.
This strategy can be replaced by an application specific implementation.

To be able to use newly added members in the cluster the coordinator facilitates rebalancing
of shards, i.e. migrate entries from one node to another. In the rebalance process the
coordinator first notifies all ``ShardRegion`` actors that a handoff for a shard has started.
That means they will start buffering incoming messages for that shard, in the same way as if the
shard location is unknown. During the rebalance process the coordinator will not answer any
requests for the location of shards that are being rebalanced, i.e. local buffering will
continue until the handoff is completed. The ``ShardRegion`` responsible for the rebalanced shard
will stop all entries in that shard by sending ``PoisonPill`` to them. When all entries have
been terminated the ``ShardRegion`` owning the entries will acknowledge the handoff as completed
to the coordinator. Thereafter the coordinator will reply to requests for the location of
the shard and thereby allocate a new home for the shard and then buffered messages in the
``ShardRegion`` actors are delivered to the new location. This means that the state of the entries
are not transferred or migrated. If the state of the entries are of importance it should be
persistent (durable), e.g. with ``akka-persistence``, so that it can be recovered at the new
location.

The logic that decides which shards to rebalance is defined in a pluggable shard
allocation strategy. The default implementation ``ShardCoordinator.LeastShardAllocationStrategy``
picks shards for handoff from the ``ShardRegion`` with most number of previously allocated shards.
They will then be allocated to the ``ShardRegion`` with least number of previously allocated shards,
i.e. new members in the cluster. There is a configurable threshold of how large the difference
must be to begin the rebalancing. This strategy can be replaced by an application specific
implementation.

The state of shard locations in the ``ShardCoordinator`` is persistent (durable) with
``akka-persistence`` to survive failures. Since it is running in a cluster ``akka-persistence``
must be configured with a distributed journal. When a crashed or unreachable coordinator
node has been removed (via down) from the cluster a new ``ShardCoordinator`` singleton
actor will take over and the state is recovered. During such a failure period shards
with known location are still available, while messages for new (unknown) shards
are buffered until the new ``ShardCoordinator`` becomes available.

As long as a sender() uses the same ``ShardRegion`` actor to deliver messages to an entry
actor the order of the messages is preserved. As long as the buffer limit is not reached
messages are delivered on a best effort basis, with at-most once delivery semantics,
in the same way as ordinary message sending. Reliable end-to-end messaging, with
at-least-once semantics can be added by using channels in ``akka-persistence``.

Some additional latency is introduced for messages targeted to new or previously
unused shards due to the round-trip to the coordinator. Rebalancing of shards may
also add latency. This should be considered when designing the application specific
shard resolution, e.g. to avoid too fine grained shards.

Proxy Only Mode
---------------

The ``ShardRegion`` actor can also be started in proxy only mode, i.e. it will not
host any entries itself, but knows how to delegate messages to the right location.
A ``ShardRegion`` starts in proxy only mode if the roles of the node does not include
the node role specified in ``akka.contrib.cluster.sharding.role`` config property
or if the specified `entryProps` is ``None`` / ``null``.

Passivation
-----------

If the state of the entries are persistent you may stop entries that are not used to
reduce memory consumption. This is done by the application specific implementation of
the entry actors for example by defining receive timeout (``context.setReceiveTimeout``).
If a message is already enqueued to the entry when it stops itself the enqueued message
in the mailbox will be dropped. To support graceful passivation without loosing such
messages the entry actor can send ``ShardRegion.Passivate`` to its parent ``ShardRegion``.
The specified wrapped message in ``Passivate`` will be sent back to the entry, which is
then supposed to stop itself. Incoming messages will be buffered by the ``ShardRegion``
between reception of ``Passivate`` and termination of the entry. Such buffered messages
are thereafter delivered to a new incarnation of the entry.

Configuration
-------------

The ``ClusterSharding`` extension can be configured with the following properties:

.. includecode:: @contribSrc@/src/main/resources/reference.conf#sharding-ext-config

Custom shard allocation strategy can be defined in an optional parameter to
``ClusterSharding.start``. See the API documentation of ``ShardAllocationStrategy``
(Scala) or ``AbstractShardAllocationStrategy`` (Java) for details of how to implement a custom
shard allocation strategy.
