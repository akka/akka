
.. _cluster:

#########
 Cluster
#########


Intro
=====

Akka Cluster provides a fault-tolerant, elastic, decentralized peer-to-peer
cluster with no single point of failure (SPOF) or single point of bottleneck
(SPOB). It implemented as a Dynamo-style system using gossip protocols,
automatic failure detection, automatic partitioning, handoff, and cluster
rebalancing. But with some differences due to the fact that it is not just
managing passive data, but actors, e.g. active, sometimes stateful, components
that have requirements on message ordering, the number of active instances in
the cluster, etc.


Terms
=====

These terms are used throughout the documentation.

**node**
  A logical member of a cluster. There could be multiple nodes on a physical
  machine. Defined by a `hostname:port` tuple.

**cluster**
  A set of nodes. Contains distributed Akka applications.

**partition**
  An actor (possibly a subtree of actors) in the Akka application that
  is distributed within the cluster.

**partition path**
  Also referred to as the actor address. Has the format `actor1/actor2/actor3`

**base node**
  The first node (with nodes in sorted order) that contains a particular partition.

**instance count**
  The number of instances of a partition in the cluster. Also referred to as the
  ``N-value`` of the partition.

**partition table**
  A mapping from partition path to base node and its ``N-value`` (i.e. its
  instance count).


Membership
==========

A cluster is made up of a set of member nodes. The identifier for each node is a
`hostname:port` pair. An Akka application is distributed over a cluster with
each node hosting some part of the application. Cluster membership and
partitioning of the application are decoupled. A node could be a member of a
cluster without hosting any actors.


Gossip
------

The cluster membership used in Akka is based on Amazon's `Dynamo`_ system and
particularly the approach taken Basho's' `Riak`_ distributed database. Cluster
membership is communicated using a `Gossip Protocol`_, where the current state
of the cluster is gossiped randomly through the cluster. Joining a cluster is
initiated by specifying a set of ``seed`` nodes with which to begin gossiping.

.. _Gossip Protocol: http://en.wikipedia.org/wiki/Gossip_protocol
.. _Dynamo: http://www.allthingsdistributed.com/files/amazon-dynamo-sosp2007.pdf
.. _Riak: http://basho.com/technology/architecture/


Vector Clocks
^^^^^^^^^^^^^

`Vector clocks`_ are an algorithm for generating a partial ordering of events in
a distributed system and detecting causality violations.

We use vector clocks to to reconcile and merge differences in cluster state
during gossiping. A vector clock is a set of (node, counter) pairs. Each update
to the cluster state has an accompanying update to the vector clock.

One problem with vector clocks is that their history can over time be very long,
which will both make comparisons take longer time as well as take up unnecessary
memory. To solve that problem we do pruning of the vector clocks according to
the `pruning algorithm`_ in Riak.

.. _Vector Clocks: http://en.wikipedia.org/wiki/Vector_clock
.. _pruning algorithm: http://wiki.basho.com/Vector-Clocks.html#Vector-Clock-Pruning

Gossip convergence
^^^^^^^^^^^^^^^^^^

Information about the cluster converges at certain points of time. This is when
all nodes have seen the same cluster state. Convergence is recognised by passing
a map from node to current state version during gossip. This information is
referred to as the gossip overview. When all versions in the overview are equal
there is convergence. Gossip convergence cannot occur while any nodes are
unreachable, either the nodes become reachable again, or the nodes need to be
moved into the ``down`` or ``removed`` states (see section on `Member states`_
below).


Failure Detector
^^^^^^^^^^^^^^^^

The failure detector is responsible for trying to detect if a node is
unreachable from the rest of the cluster. For this we are using an
implementation of `The Phi Accrual Failure Detector`_ by Hayashibara et al.

An accrual failure detector decouple monitoring and interpretation. That makes
them applicable to a wider area ofQ scenarios and more adequate to build generic
failure detection services. The idea is that it is keeping a history of failure
statistics, calculated from heartbeats received from the gossip protocol, and is
trying to do educated guesses by taking multiple factors, and how they
accumulate over time, into account in order to come up with a better guess if a
specific node is up or down. Rather than just answering "yes" or "no" to the
question "is the node down?"  it returns a ``phi`` value representing the
likelihood that the node is down.

The ``threshold`` that is the basis for the calculation is configurable by the
user. A low ``threshold`` is prone to generate many wrong suspicions but ensures
a quick detection in the event of a real crash. Conversely, a high ``threshold``
generates fewer mistakes but needs more time to detect actual crashes. The
default ``threshold`` is 8 and is appropriate for most situations. However in
cloud environments, such as Amazon EC2, the value could be increased to 12 in
order to account for network issues that sometimes occur on such platforms.

.. _The Phi Accrual Failure Detector: http://ddg.jaist.ac.jp/pub/HDY+04.pdf

Leader
^^^^^^

After gossip convergence a leader for the cluster can be determined. There is no
leader election process, the leader can always be recognised deterministically
by any node whenever there is gossip convergence. The leader is simply the first
node in sorted order that is able to take the leadership role, where the only
allowed member states for a leader are ``up`` or ``leaving`` (see below for more
information about member states).

The role of the leader is to shift members in and out of the cluster, changing
``joining`` members to the ``up`` state or ``exiting`` members to the
``removed`` state, and to schedule rebalancing across the cluster. Currently
leader actions are only triggered by receiving a new cluster state with gossip
convergence but it may also be possible for the user to explicitly rebalance the
cluster by specifying migrations, or to rebalance the cluster automatically
based on metrics gossiped by the member nodes.

The leader also has the power, if configured so, to "auto-down" a node that
according to the Failure Detector is considered unreachable. This means setting
the unreachable node status to ``down`` automatically.


Gossip protocol
^^^^^^^^^^^^^^^

A variation of *push-pull gossip* is used to reduce the amount of gossip
information sent around the cluster. In push-pull gossip a digest is sent
representing current versions but not actual values; the recipient of the gossip
can then send back any values for which it has newer versions and also request
values for which it has outdated versions. Akka uses a single shared state with
a vector clock for versioning, so the variant of push-pull gossip used in Akka
makes use of the gossip overview (containing the current state versions for all
nodes) to only push the actual state as needed. This also allows any node to
easily determine which other nodes have newer or older information, not just the
nodes involved in a gossip exchange.

Periodically, the default is every 1 second, each node chooses another random
node to initiate a round of gossip with. The choice of node is random but can
also include extra gossiping for unreachable nodes, seed nodes, and nodes with
either newer or older state versions.

The gossip overview contains the current state version for all nodes and also a
list of unreachable nodes. Whenever a node receives a gossip overview it updates
the `Failure Detector`_ with the liveness information.

The nodes defined as ``seed`` nodes are just regular member nodes whose only
"special role" is to function as contact points in the cluster and to help
breaking logical partitions as seen in the gossip algorithm defined below.

During each round of gossip exchange the following process is used:

1. Gossip to random live node (if any)

2. Gossip to random unreachable node with certain probability depending on the
   number of unreachable and live nodes

3. If the node gossiped to at (1) was not a ``seed`` node, or the number of live
   nodes is less than number of seeds, gossip to random ``seed`` node with
   certain probability depending on number of unreachable, seed, and live nodes.

4. Gossip to random node with newer or older state information, based on the
   current gossip overview, with some probability (?)

The gossiper only sends the gossip overview to the chosen node. The recipient of
the gossip can use the gossip overview to determine whether:

1. it has a newer version of the gossip state, in which case it sends that back
   to the gossiper, or

2. it has an outdated version of the state, in which case the recipient requests
   the current state from the gossiper

If the recipient and the gossip have the same version then the gossip state is
not sent or requested.

The main structures used in gossiping are the gossip overview and the gossip
state::

  GossipOverview {
    versions: Map[Node, VectorClock],
    unreachable: Set[Node]
  }

 GossipState {
    version: VectorClock,
    members: SortedSet[Member],
    partitions: Tree[PartitionPath, Node],
    pending: Set[PartitionChange],
    meta: Option[Map[String, Array[Byte]]]
  }

Some of the other structures used are::

  Node = InetSocketAddress

  Member {
    node: Node,
    state: MemberState
  }

  MemberState = Joining | Up | Leaving | Exiting | Down | Removed

  PartitionChange {
    from: Node,
    to: Node,
    path: PartitionPath,
    status: PartitionChangeStatus
  }

  PartitionChangeStatus = Awaiting | Complete


Membership lifecycle
--------------------

A node begins in the ``joining`` state. Once all nodes have seen that the new
node is joining (through gossip convergence) the leader will set the member
state to ``up`` and can start assigning partitions to the new node.

If a node is leaving the cluster in a safe, expected manner then it switches to
the ``leaving`` state. The leader will reassign partitions across the cluster
(it is possible for a leaving node to itself be the leader). When all partition
handoff has completed then the node will change to the ``exiting`` state. Once
all nodes have seen the exiting state (convergence) the leader will remove the
node from the cluster, marking it as ``removed``.

A node can also be removed forcefully by moving it directly to the ``removed``
state using the ``remove`` action. The cluster will rebalance based on the new
cluster membership.

If a node is unreachable then gossip convergence is not possible and therefore
any leader actions are also not possible (for instance, allowing a node to
become a part of the cluster, or changing actor distribution). To be able to
move forward the state of the unreachable nodes must be changed. If the
unreachable node is experiencing only transient difficulties then it can be
explicitly marked as ``down`` using the ``down`` user action. When this node
comes back up and begins gossiping it will automatically go through the joining
process again. If the unreachable node will be permanently down then it can be
removed from the cluster directly with the ``remove`` user action. The cluster
can also *auto-down* a node using the accrual failure detector.

This means that nodes can join and leave the cluster at any point in time,
e.g. provide cluster elasticity.


State diagram for the member states
^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^

.. image:: images/member-states.png


Member states
^^^^^^^^^^^^^

- **joining**
    transient state when joining a cluster

- **up**
    normal operating state

- **leaving** / **exiting**
    states during graceful removal

- **removed**
    tombstone state (no longer a member)

- **down**
    marked as down/offline/unreachable


User actions
^^^^^^^^^^^^

- **join**
    join a single node to a cluster - can be explicit or automatic on
    startup if a list of seed nodes have been specified in the configuration

- **leave**
    tell a node to leave the cluster gracefully

- **down**
    mark a node as temporarily down

- **remove**
    remove a node from the cluster immediately


Leader actions
^^^^^^^^^^^^^^

The leader has the following duties:

- shifting members in and out of the cluster

  - joining -> up

  - exiting -> removed

- partition distribution

  - scheduling handoffs (pending changes)

  - setting the partition table (partition path -> base node)

  - Automatic rebalancing based on runtime metrics in the system (such as CPU,
    RAM, Garbage Collection, mailbox depth etc.)


Partitioning
============

Each partition (an actor or actor subtree) in the actor system is assigned to a
base node. The mapping from partition path (actor address on the format "a/b/c")
to base node is stored in the partition table and is maintained as part of the
cluster state through the gossip protocol. The partition table is only updated
by the leader node. If the partition has a configured instance count, referred
to as the ``N-value``, greater than one, then the location of the other
instances can be found deterministically by counting from the base node. (The
``N-value`` is larger than 1 when a actor is configured to be routed.) The first
instance will be found on the base node, and the other instances on the next N-1
nodes, given the nodes in sorted order.

TODO: discuss how different N values within the tree work (especially subtrees
with a greater or lesser N value). A simple implementation would only allow the
highest-up-the-tree, non-singular (greater than one) value to be used for any
subtree.

When rebalancing is required the leader will schedule handoffs, gossiping a set
of pending changes, and when each change is complete the leader will update the
partition table.

TODO: look further into how actors will be distributed and also avoiding
unnecessary migrations just to create a more balanced cluster.


Handoff
-------

Handoff for an actor-based system is different than for a data-based system. The
most important point is that message ordering (from a given node to a given
actor instance) may need to be maintained. If an actor is a singleton actor
(only one instance possible throughout the cluster) then the cluster may also
need to assure that there is only one such actor active at any one time. Both of
these situations can be handled by forwarding and buffering messages during
transitions.

A *graceful handoff* (one where the previous host node is up and running during
the handoff), given a previous host node ``N1``, a new host node ``N2``, and an
actor partition ``A`` to be migrated from ``N1`` to ``N2``, has this general
structure:

  1. the leader sets a pending change for ``N1`` to handoff ``A`` to ``N2``

  2. ``N1`` notices the pending change and sends an initialization message to ``N2``

  3. in response ``N2`` creates ``A`` and sends back a ready message

  4. after receiving the ready message ``N1`` marks the change as
     complete and shuts down ``A``

  5. the leader sees the migration is complete and updates the partition table

  6. all nodes eventually see the new partitioning and use ``N2``


Transitions
^^^^^^^^^^^

There are transition times in the handoff process where different approaches can
be used to give different guarantees.


Migration transition
~~~~~~~~~~~~~~~~~~~~

The first transition starts when ``N1`` initiates the moving of ``A`` and ends
when ``N1`` receives the ready message, and is referred to as the *migration
transition*.

The first question is; during the migration transition, should:

- ``N1`` continue to process messages for ``A``?

- Or is it important that no messages for ``A`` are processed on
  ``N1`` once migration begins?

If it is okay for the previous host node ``N1`` to process messages during
migration then there is nothing that needs to be done at this point.

If no messages are to be processed on the previous host node during migration
then there are two possibilities: the messages are forwarded to the new host and
buffered until the actor is ready, or the messages are simply dropped by
terminating the actor and allowing the normal dead letter process to be used.


Update transition
~~~~~~~~~~~~~~~~~

The second transition begins when the migration is marked as complete and ends
when all nodes have the updated partition table (when all nodes will use ``N2``
as the host for ``A``), e.g. we have convergence, and is referred to as the
*update transition*.

Once the update transition begins ``N1`` can forward any messages it receives
for ``A`` to the new host ``N2``. The question is whether or not message
ordering needs to be preserved. If messages sent to the previous host node
``N1`` are being forwarded, then it is possible that a message sent to ``N1``
could be forwarded after a direct message to the new host ``N2``, breaking
message ordering from a client to actor ``A``.

In this situation ``N2`` can keep a buffer for messages per sending node. Each
buffer is flushed and removed when an acknowledgement (``ack``) message has been
received. When each node in the cluster sees the partition update it first sends
an ``ack`` message to the previous host node ``N1`` before beginning to use
``N2`` as the new host for ``A``. Any messages sent from the client node
directly to ``N2`` will be buffered. ``N1`` can count down the number of acks to
determine when no more forwarding is needed. The ``ack`` message from any node
will always follow any other messages sent to ``N1``. When ``N1`` receives the
``ack`` message it also forwards it to ``N2`` and again this ``ack`` message
will follow any other messages already forwarded for ``A``. When ``N2`` receives
an ``ack`` message, the buffer for the sending node can be flushed and removed.
Any subsequent messages from this sending node can be queued normally. Once all
nodes in the cluster have acknowledged the partition change and ``N2`` has
cleared all buffers, the handoff is complete and message ordering has been
preserved. In practice the buffers should remain small as it is only those
messages sent directly to ``N2`` before the acknowledgement has been forwarded
that will be buffered.


Graceful handoff
^^^^^^^^^^^^^^^^

A more complete process for graceful handoff would be:

  1. the leader sets a pending change for ``N1`` to handoff ``A`` to ``N2``


  2. ``N1`` notices the pending change and sends an initialization message to
     ``N2``. Options:

     a. keep ``A`` on ``N1`` active and continuing processing messages as normal

     b. ``N1`` forwards all messages for ``A`` to ``N2``

     c. ``N1`` drops all messages for ``A`` (terminate ``A`` with messages
        becoming dead letters)


  3. in response ``N2`` creates ``A`` and sends back a ready message. Options:

     a. ``N2`` simply processes messages for ``A`` as normal

     b. ``N2`` creates a buffer per sending node for ``A``. Each buffer is
        opened (flushed and removed) when an acknowledgement for the sending
        node has been received (via ``N1``)


  4. after receiving the ready message ``N1`` marks the change as complete. Options:

     a. ``N1`` forwards all messages for ``A`` to ``N2`` during the update transition

     b. ``N1`` drops all messages for ``A`` (terminate ``A`` with messages
        becoming dead letters)


  5. the leader sees the migration is complete and updates the partition table


  6. all nodes eventually see the new partitioning and use ``N2``

     i. each node sends an acknowledgement message to ``N1``

     ii. when ``N1`` receives the acknowledgement it can count down the pending
         acknowledgements and remove forwarding when complete

     iii. when ``N2`` receives the acknowledgement it can open the buffer for the
          sending node (if buffers are used)


The default approach is to take options 2a, 3a, and 4a - allowing ``A`` on
``N1`` to continue processing messages during migration and then forwarding any
messages during the update transition. This assumes stateless actors that do not
have a dependency on message ordering from any given source.

- If an actor has a distributed durable mailbox then nothing needs to be done,
  other than migrating the actor.

- If message ordering needs to be maintained during the update transition then
  option 3b can be used, creating buffers per sending node.

- If the actors are robust to message send failures then the dropping messages
  approach can be used (with no forwarding or buffering needed).

- If an actor is a singleton (only one instance possible throughout the cluster)
  and state is transfered during the migration initialization, then options 2b
  and 3b would be required.

Support for stateful singleton actors will come in future releases of Akka, most
likely Akka 2.2.
