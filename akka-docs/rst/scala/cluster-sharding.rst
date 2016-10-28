.. _cluster_sharding_scala:

Cluster Sharding
================

Cluster sharding is useful when you need to distribute actors across several nodes in the cluster and want to
be able to interact with them using their logical identifier, but without having to care about
their physical location in the cluster, which might also change over time.

It could for example be actors representing Aggregate Roots in Domain-Driven Design terminology.
Here we call these actors "entities". These actors typically have persistent (durable) state,
but this feature is not limited to actors with persistent state.

Cluster sharding is typically used when you have many stateful actors that together consume
more resources (e.g. memory) than fit on one machine. If you only have a few stateful actors
it might be easier to run them on a :ref:`cluster-singleton-scala` node.

In this context sharding means that actors with an identifier, so called entities,
can be automatically distributed across multiple nodes in the cluster. Each entity
actor runs only at one place, and messages can be sent to the entity without requiring
the sender to know the location of the destination actor. This is achieved by sending
the messages via a ``ShardRegion`` actor provided by this extension, which knows how
to route the message with the entity id to the final destination.

Cluster sharding will not be active on members with status :ref:`WeaklyUp <weakly_up_scala>` 
if that feature is enabled.

.. warning::
   **Don't use Cluster Sharding together with Automatic Downing**,
   since it allows the cluster to split up into two separate clusters, which in turn will result
   in *multiple shards and entities* being started, one in each separate cluster! 
   See :ref:`automatic-vs-manual-downing-java`.

An Example
----------

This is how an entity actor may look like:

.. includecode:: ../../../akka-cluster-sharding/src/multi-jvm/scala/akka/cluster/sharding/ClusterShardingSpec.scala#counter-actor

The above actor uses event sourcing and the support provided in ``PersistentActor`` to store its state.
It does not have to be a persistent actor, but in case of failure or migration of entities between nodes it must be able to recover
its state if it is valuable.

Note how the ``persistenceId`` is defined. The name of the actor is the entity identifier (utf-8 URL-encoded).
You may define it another way, but it must be unique.

When using the sharding extension you are first, typically at system startup on each node
in the cluster, supposed to register the supported entity types with the ``ClusterSharding.start``
method. ``ClusterSharding.start`` gives you the reference which you can pass along.

.. includecode:: ../../../akka-cluster-sharding/src/multi-jvm/scala/akka/cluster/sharding/ClusterShardingSpec.scala#counter-start

The ``extractEntityId`` and ``extractShardId`` are two application specific functions to extract the entity
identifier and the shard identifier from incoming messages.

.. includecode:: ../../../akka-cluster-sharding/src/multi-jvm/scala/akka/cluster/sharding/ClusterShardingSpec.scala#counter-extractor

This example illustrates two different ways to define the entity identifier in the messages:

 * The ``Get`` message includes the identifier itself.
 * The ``EntityEnvelope`` holds the identifier, and the actual message that is
   sent to the entity actor is wrapped in the envelope.

Note how these two messages types are handled in the ``extractEntityId`` function shown above.
The message sent to the entity actor is the second part of the tuple return by the ``extractEntityId`` and that makes it 
possible to unwrap envelopes if needed.

A shard is a group of entities that will be managed together. The grouping is defined by the
``extractShardId`` function shown above. For a specific entity identifier the shard identifier must always 
be the same. 

Creating a good sharding algorithm is an interesting challenge in itself. Try to produce a uniform distribution, 
i.e. same amount of entities in each shard. As a rule of thumb, the number of shards should be a factor ten greater 
than the planned maximum number of cluster nodes. Less shards than number of nodes will result in that some nodes 
will not host any shards. Too many shards will result in less efficient management of the shards, e.g. rebalancing
overhead, and increased latency because the coordinator is involved in the routing of the first message for each
shard. The sharding algorithm must be the same on all nodes in a running cluster. It can be changed after stopping
all nodes in the cluster.

A simple sharding algorithm that works fine in most cases is to take the absolute value of the ``hashCode`` of
the entity identifier modulo number of shards. As a convenience this is provided by the 
``ShardRegion.HashCodeMessageExtractor``.

Messages to the entities are always sent via the local ``ShardRegion``. The ``ShardRegion`` actor reference for a
named entity type is returned by ``ClusterSharding.start`` and it can also be retrieved with ``ClusterSharding.shardRegion``.
The ``ShardRegion`` will lookup the location of the shard for the entity if it does not already know its location. It will
delegate the message to the right node and it will create the entity actor on demand, i.e. when the
first message for a specific entity is delivered.

.. includecode:: ../../../akka-cluster-sharding/src/multi-jvm/scala/akka/cluster/sharding/ClusterShardingSpec.scala#counter-usage

A more comprehensive sample is available in the `Lightbend Activator <http://www.lightbend.com/platform/getstarted>`_
tutorial named `Akka Cluster Sharding with Scala! <http://www.lightbend.com/activator/template/akka-cluster-sharding-scala>`_.

How it works
------------

The ``ShardRegion`` actor is started on each node in the cluster, or group of nodes
tagged with a specific role. The ``ShardRegion`` is created with two application specific
functions to extract the entity identifier and the shard identifier from incoming messages.
A shard is a group of entities that will be managed together. For the first message in a
specific shard the ``ShardRegion`` request the location of the shard from a central coordinator,
the ``ShardCoordinator``.

The ``ShardCoordinator`` decides which ``ShardRegion`` shall own the ``Shard`` and informs
that ``ShardRegion``. The region will confirm this request and create the ``Shard`` supervisor
as a child actor. The individual ``Entities`` will then be created when needed by the ``Shard``
actor. Incoming messages thus travel via the ``ShardRegion`` and the ``Shard`` to the target
``Entity``.

If the shard home is another ``ShardRegion`` instance messages will be forwarded
to that ``ShardRegion`` instance instead. While resolving the location of a
shard incoming messages for that shard are buffered and later delivered when the
shard home is known. Subsequent messages to the resolved shard can be delivered
to the target destination immediately without involving the ``ShardCoordinator``.

Scenario 1:

#. Incoming message M1 to ``ShardRegion`` instance R1.
#. M1 is mapped to shard S1. R1 doesn't know about S1, so it asks the coordinator C for the location of S1.
#. C answers that the home of S1 is R1.
#. R1 creates child actor for the entity E1 and sends buffered messages for S1 to E1 child
#. All incoming messages for S1 which arrive at R1 can be handled by R1 without C. It creates entity children as needed, and forwards messages to them.

Scenario 2:

#. Incoming message M2 to R1.
#. M2 is mapped to S2. R1 doesn't know about S2, so it asks C for the location of S2.
#. C answers that the home of S2 is R2.
#. R1 sends buffered messages for S2 to R2
#. All incoming messages for S2 which arrive at R1 can be handled by R1 without C. It forwards messages to R2.
#. R2 receives message for S2, ask C, which answers that the home of S2 is R2, and we are in Scenario 1 (but for R2).

To make sure that at most one instance of a specific entity actor is running somewhere
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
of shards, i.e. migrate entities from one node to another. In the rebalance process the
coordinator first notifies all ``ShardRegion`` actors that a handoff for a shard has started.
That means they will start buffering incoming messages for that shard, in the same way as if the
shard location is unknown. During the rebalance process the coordinator will not answer any
requests for the location of shards that are being rebalanced, i.e. local buffering will
continue until the handoff is completed. The ``ShardRegion`` responsible for the rebalanced shard
will stop all entities in that shard by sending the specified ``handOffStopMessage`` 
(default ``PoisonPill``) to them. When all entities have been terminated the ``ShardRegion``
owning the entities will acknowledge the handoff as completed to the coordinator. 
Thereafter the coordinator will reply to requests for the location of
the shard and thereby allocate a new home for the shard and then buffered messages in the
``ShardRegion`` actors are delivered to the new location. This means that the state of the entities
are not transferred or migrated. If the state of the entities are of importance it should be
persistent (durable), e.g. with :ref:`persistence-scala`, so that it can be recovered at the new
location.

The logic that decides which shards to rebalance is defined in a pluggable shard
allocation strategy. The default implementation ``ShardCoordinator.LeastShardAllocationStrategy``
picks shards for handoff from the ``ShardRegion`` with most number of previously allocated shards.
They will then be allocated to the ``ShardRegion`` with least number of previously allocated shards,
i.e. new members in the cluster. There is a configurable threshold of how large the difference
must be to begin the rebalancing. This strategy can be replaced by an application specific
implementation.

The state of shard locations in the ``ShardCoordinator`` is persistent (durable) with
:ref:`persistence-scala` to survive failures. Since it is running in a cluster :ref:`persistence-scala`
must be configured with a distributed journal. When a crashed or unreachable coordinator
node has been removed (via down) from the cluster a new ``ShardCoordinator`` singleton
actor will take over and the state is recovered. During such a failure period shards
with known location are still available, while messages for new (unknown) shards
are buffered until the new ``ShardCoordinator`` becomes available.

As long as a sender uses the same ``ShardRegion`` actor to deliver messages to an entity
actor the order of the messages is preserved. As long as the buffer limit is not reached
messages are delivered on a best effort basis, with at-most once delivery semantics,
in the same way as ordinary message sending. Reliable end-to-end messaging, with
at-least-once semantics can be added by using ``AtLeastOnceDelivery``  in :ref:`persistence-scala`.

Some additional latency is introduced for messages targeted to new or previously
unused shards due to the round-trip to the coordinator. Rebalancing of shards may
also add latency. This should be considered when designing the application specific
shard resolution, e.g. to avoid too fine grained shards.

Distributed Data Mode
---------------------

Instead of using :ref:`persistence-scala` it is possible to use the :ref:`distributed_data_scala` module
as storage for the state of the sharding coordinator. In such case the state of the 
``ShardCoordinator`` will be replicated inside a cluster by the :ref:`distributed_data_scala` module with
``WriteMajority``/``ReadMajority`` consistency.

This mode can be enabled by setting configuration property::

    akka.cluster.sharding.state-store-mode = ddata 

It is using the Distributed Data extension that must be running on all nodes in the cluster.
Therefore you should add that extension to the configuration to make sure that it is started
on all nodes::

    akka.extensions += "akka.cluster.ddata.DistributedData"

You must explicitly add the ``akka-distributed-data-experimental`` dependency to your build if
you use this mode. It is possible to remove ``akka-persistence`` dependency from a project if it
is not used in user code and ``remember-entities`` is ``off``.
Using it together with ``Remember Entities`` shards will be recreated after rebalancing, however will
not be recreated after a clean cluster start as the Sharding Coordinator state is empty after a clean cluster
start when using ddata mode. When ``Remember Entities`` is ``on`` Sharding Region always keeps data usig persistence,
no matter how ``State Store Mode`` is set.

.. warning::

  The ``ddata`` mode is considered as **“experimental”** as of its introduction in Akka 2.4.0, since
  it depends on the experimental Distributed Data module.

Startup after minimum number of members
---------------------------------------

It's good to use Cluster Sharding with the Cluster setting ``akka.cluster.min-nr-of-members`` or
``akka.cluster.role.<role-name>.min-nr-of-members``. That will defer the allocation of the shards
until at least that number of regions have been started and registered to the coordinator. This
avoids that many shards are allocated to the first region that registers and only later are 
rebalanced to other nodes.

See :ref:`min-members_scala` for more information about ``min-nr-of-members``.

Proxy Only Mode
---------------

The ``ShardRegion`` actor can also be started in proxy only mode, i.e. it will not
host any entities itself, but knows how to delegate messages to the right location.
A ``ShardRegion`` is started in proxy only mode with the method ``ClusterSharding.startProxy``
method.

Passivation
-----------

If the state of the entities are persistent you may stop entities that are not used to
reduce memory consumption. This is done by the application specific implementation of
the entity actors for example by defining receive timeout (``context.setReceiveTimeout``).
If a message is already enqueued to the entity when it stops itself the enqueued message
in the mailbox will be dropped. To support graceful passivation without losing such
messages the entity actor can send ``ShardRegion.Passivate`` to its parent ``Shard``.
The specified wrapped message in ``Passivate`` will be sent back to the entity, which is
then supposed to stop itself. Incoming messages will be buffered by the ``Shard``
between reception of ``Passivate`` and termination of the entity. Such buffered messages
are thereafter delivered to a new incarnation of the entity.

Remembering Entities
--------------------

The list of entities in each ``Shard`` can be made persistent (durable) by setting
the ``rememberEntities`` flag to true in ``ClusterShardingSettings`` when calling 
``ClusterSharding.start``. When configured to remember entities, whenever a ``Shard`` 
is rebalanced onto another node or recovers after a crash it will recreate all the
entities which were previously running in that ``Shard``. To permanently stop entities, 
a ``Passivate`` message must be sent to the parent of the entity actor, otherwise the
entity will be automatically restarted after the entity restart backoff specified in 
the configuration.

When ``rememberEntities`` is set to false, a ``Shard`` will not automatically restart any entities
after a rebalance or recovering from a crash. Entities will only be started once the first message
for that entity has been received in the ``Shard``. Entities will not be restarted if they stop without
using a ``Passivate``.

Note that the state of the entities themselves will not be restored unless they have been made persistent,
e.g. with :ref:`persistence-scala`.

Supervision
-----------

If you need to use another ``supervisorStrategy`` for the entity actors than the default (restarting) strategy
you need to create an intermediate parent actor that defines the ``supervisorStrategy`` to the
child entity actor.

.. includecode:: ../../../akka-cluster-sharding/src/multi-jvm/scala/akka/cluster/sharding/ClusterShardingSpec.scala#supervisor

You start such a supervisor in the same way as if it was the entity actor.

.. includecode:: ../../../akka-cluster-sharding/src/multi-jvm/scala/akka/cluster/sharding/ClusterShardingSpec.scala#counter-supervisor-start

Note that stopped entities will be started again when a new message is targeted to the entity.

Graceful Shutdown
-----------------

You can send the message ``ShardRegion.GracefulShutdown`` message to the ``ShardRegion`` actor to handoff all shards that are hosted by that ``ShardRegion`` and then the
``ShardRegion`` actor will be stopped. You can ``watch`` the ``ShardRegion`` actor to know when it is completed.
During this period other regions will buffer messages for those shards in the same way as when a rebalance is
triggered by the coordinator. When the shards have been stopped the coordinator will allocate these shards elsewhere.

When the ``ShardRegion`` has terminated you probably want to ``leave`` the cluster, and shut down the ``ActorSystem``.

This is how to do that: 

.. includecode:: ../../../akka-cluster-sharding/src/multi-jvm/scala/akka/cluster/sharding/ClusterShardingGracefulShutdownSpec.scala#graceful-shutdown 

.. _RemoveInternalClusterShardingData-scala:

Removal of Internal Cluster Sharding Data
-----------------------------------------

The Cluster Sharding coordinator stores the locations of the shards using Akka Persistence.
This data can safely be removed when restarting the whole Akka Cluster.
Note that this is not application data.

There is a utility program ``akka.cluster.sharding.RemoveInternalClusterShardingData``
that removes this data.
 
.. warning::

  Never use this program while there are running Akka Cluster nodes that are
  using Cluster Sharding. Stop all Cluster nodes before using this program.

It can be needed to remove the data if the Cluster Sharding coordinator
cannot startup because of corrupt data, which may happen if accidentally
two clusters were running at the same time, e.g. caused by using auto-down
and there was a network partition.

.. warning::
   **Don't use Cluster Sharding together with Automatic Downing**,
   since it allows the cluster to split up into two separate clusters, which in turn will result
   in *multiple shards and entities* being started, one in each separate cluster! 
   See :ref:`automatic-vs-manual-downing-scala`.

Use this program as a standalone Java main program::
 
    java -classpath <jar files, including akka-cluster-sharding>
      akka.cluster.sharding.RemoveInternalClusterShardingData
        -2.3 entityType1 entityType2 entityType3

The program is included in the ``akka-cluster-sharding`` jar file. It
is easiest to run it with same classpath and configuration as your ordinary
application. It can be run from sbt or maven in similar way.

Specify the entity type names (same as you use in the ``start`` method
of ``ClusterSharding``) as program arguments.

If you specify ``-2.3`` as the first program argument it will also try
to remove data that was stored by Cluster Sharding in Akka 2.3.x using
different persistenceId.

Dependencies
------------

To use the Cluster Sharding you must add the following dependency in your project.

sbt::

    "com.typesafe.akka" %% "akka-cluster-sharding" % "@version@" @crossString@

maven::

  <dependency>
    <groupId>com.typesafe.akka</groupId>
    <artifactId>akka-cluster-sharding_@binVersion@</artifactId>
    <version>@version@</version>
  </dependency>

Configuration
-------------

The ``ClusterSharding`` extension can be configured with the following properties. These configuration
properties are read by the ``ClusterShardingSettings`` when created with a ``ActorSystem`` parameter.
It is also possible to amend the ``ClusterShardingSettings`` or create it from another config section
with the same layout as below. ``ClusterShardingSettings`` is a parameter to the ``start`` method of
the ``ClusterSharding`` extension, i.e. each each entity type can be configured with different settings
if needed.

.. includecode:: ../../../akka-cluster-sharding/src/main/resources/reference.conf#sharding-ext-config

Custom shard allocation strategy can be defined in an optional parameter to
``ClusterSharding.start``. See the API documentation of ``ShardAllocationStrategy`` for details of 
how to implement a custom shard allocation strategy.


Inspecting cluster sharding state
---------------------------------
Two requests to inspect the cluster state are available:

``ShardRegion.GetShardRegionState`` which will return a ``ShardRegion.CurrentShardRegionState`` that contains
the identifiers of the shards running in a Region and what entities are alive for each of them.

``ShardRegion.GetClusterShardingStats`` which will query all the regions in the cluster and return
a ``ShardRegion.ClusterShardingStats`` containing the identifiers of the shards running in each region and a count
of entities that are alive in each shard.

The purpose of these messages is testing and monitoring, they are not provided to give access to
directly sending messages to the individual entities.
