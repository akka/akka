.. _split_brain_resolver_java:

######################
 Split Brain Resolver
######################

When operating an Akka cluster you must consider how to handle 
`network partitions <http://en.wikipedia.org/wiki/Network_partition>`_ (a.k.a. split brain scenarios)
and machine crashes (including JVM and hardware failures). This is crucial for correct behavior if 
you use Cluster Singleton or Cluster Sharding, especially together with Akka Persistence.

.. note:: This is a feature of the `Typesafe Reactive Platform <http://www.typesafe.com/products/typesafe-reactive-platform>`_
          that is exclusively available for 
          `Typesafe Project Success Subscription <http://www.typesafe.com/subscription>`_ customers.

To use the Split Brain Resolver feature you must  
`install Typesafe Reactive Platform <https://together.typesafe.com/products/reactivePlatform>`_. If you use
``akka-contrib``, make sure that you use version ``@version@`` (instead of the OSS version).

The Problem
===========

A fundamental problem in distributed systems is that network partitions (split brain scenarios) and 
machine crashes are indistinguishable for the observer, i.e. a node can observe that there is a problem 
with another node, but it cannot tell if it has crashed and will never be available again or if there is 
a network issue that might or might not heal again after a while. Temporary and permanent failures are 
indistinguishable because decisions must be made in finite time, and there always exists a temporary
failure that lasts longer than the time limit for the decision.

A third type of problem is if a process is unresponsive, e.g. because of overload, CPU starvation or
long garbage collection pauses. This is also indistinguishable from network partitions and crashes.
The only signal we have for decision is "no reply in given time for heartbeats" and this means that
every phenomena causing delays or lost heartbeats are indistinguishable from each other and must be 
handled in the same way.

When there is a crash we would like to remove the crashed node immediately from the cluster membership.
When there is a network partition or unresponsive process we would like to wait for a while in the hope 
of that it is a transient problem that will heal again, but at some point we must give up and continue with 
the nodes on one side of the partition and shut down nodes on the other side. Also, certain features are
not fully available during partitions so it might not matter that the partition is transient or not if
it just takes too long. Those two goals are in conflict with each other and there is a trade-off 
between how quickly we can remove a crashed node and premature action on transient network partitions.

This is a difficult problem to solve given that the nodes on the different sides of the network partition
cannot communicate with each other. We must ensure that both sides can make this decision by themselves and
that they take the same decision about which part will keep running and which part will shut itself down. 

The Akka cluster has a failure detector that will notice network partitions and machine crashes (but it 
cannot distinguish the two). It uses periodic heartbeat messages to check if other nodes are available
and healthy. These observations by the failure detector are referred to as a node being *unreachable*
and it may become *reachable* again if the failure detector observes that it can communicate with it again.  

The failure detector in itself is not enough for making the right decision in all situations.
The naïve approach is to remove an unreachable node from the cluster membership after a timeout.
This works great for crashes and short transient network partitions, but not for long network
partitions. Both sides of the network partition will see the other side as unreachable and 
after a while remove it from its cluster membership. Since this happens on both sides the result
is that two separate disconnected clusters have been created.
This approach is provided by the opt-in (off by default) auto-down feature in the OSS version of
Akka Cluster.

If you use the timeout based auto-down feature in combination with Cluster Singleton or Cluster Sharding
that would mean that two singleton instances or two sharded entities with same identifier would be running.
One would be running: one in each cluster. 
For example when used together with Akka Persistence that could result in that two instances of a 
persistent actor with the same ``persistenceId`` are running and writing concurrently to the
same stream of persistent events, which will have fatal consequences when replaying these events.

The default setting in Akka Cluster is to not remove unreachable nodes automatically and
the :ref:`recommendation <automatic-vs-manual-downing-java>` is that the decision of what to 
do should be taken by a human operator or an external monitoring system. This is a valid solution, 
but not very convenient if you do not have this staff or external system for other reasons.

If the unreachable nodes are not downed at all they will still be part of the cluster membership.
Meaning that Cluster Singleton and Cluster Sharding will not failover to another node. While there 
are unreachable nodes new nodes that are joining the cluster will not be promoted to full worthy 
members (with status Up). Similarly, leaving members will not be removed until all unreachable 
nodes have been resolved. In other words, keeping unreachable members for an unbounded time is
undesirable.

With that introduction of the problem domain it is time to look at the provided strategies for
handling network partition, unresponsive nodes and crashed nodes.

Strategies
==========

There is not a "one size fits all" solution to this problem. You have to pick a strategy that fits
the characteristics of your system. Every strategy has a failure scenario where it makes a "wrong"
decision. This section describes the different strategies and guidelines of when to use what.

You enable a strategy with the configuration property ``akka.cluster.split-brain-resolver.active-strategy``.

.. note:: You must also remove auto-down configuration, remove this property (or set to off): ``akka.cluster.auto-down-unreachable-after``

All strategies are inactive until the cluster membership and the information about unreachable nodes
have been stable for a certain time period. Continuously adding more nodes while there is a network
partition does not influence this timeout, since the status of those nodes will not be changed to Up
while there are unreachable nodes. Joining nodes are not counted in the logic of the strategies.  

.. includecode:: ../../../akka-cluster/src/main/resources/reference.conf#split-brain-resolver

Set the ``stable-after`` to a shorter duration to have quicker removal of crashed nodes, at the price
of risking too early action on transient network partitions that otherwise would have healed. Do not
set this to a shorter duration than the membership dissemination time in the cluster, which depends
on the cluster size. Recommended minimum duration for different cluster sizes:

============ ============
cluster size stable-after
============ ============
5            7 s
10           10 s
20           13 s
50           17 s
100          20 s
1000         30 s
============ ============

The different strategies may have additional settings that are described below.

.. note:: It is important that you use the same configuration on all nodes.

The side of the split that decides to shut itself down will use the cluster *down* command 
to initiate the removal of a cluster member. When that has been spread among the reachable nodes 
it will be removed from the cluster membership. That does not automatically shut down the 
``ActorSystem`` or exit the JVM. To implement that you have to use the ``registerOnMemberRemoved``
callback.

This is how to shut down the ``ActorSystem`` and thereafter exit the JVM:

.. includecode:: ../../../akka-samples/akka-sample-cluster-java/src/main/java/sample/cluster/factorial/FactorialFrontendMain.java#registerOnRemoved

Static Quorum
-------------

The strategy named ``static-quorum`` will down the unreachable nodes if the number of remaining
nodes are greater than or equal to a configured ``quorum-size``. Otherwise it will down the reachable nodes,
i.e. it will shut down that side of the partition. In other words, the ``quorum-size`` defines the minimum 
number of nodes that the cluster must have to be operational. 

This strategy is a good choice when you have a fixed number of nodes in the cluster, or when you can
define a fixed number of nodes with a certain role.

For example, in a 9 node cluster you will configure the ``quorum-size`` to 5. If there is a network split
of 4 and 5 nodes the side with 5 nodes will survive and the other 4 nodes will be downed. Thereafter,
in the 5 node cluster, no more failures can be handled, because the remaining cluster size would be
less than 5. In the case of another failure in that 5 node cluster all nodes will be downed.

Therefore it is important that you join new nodes when old nodes have been removed.

Another consequence of this is that if there are unreachable nodes when starting up the cluster, 
before reaching this limit, the cluster may shut itself down immediately. This is not an issue
if you start all nodes at approximately the same time or use the ``akka.cluster.min-nr-of-members``
to define required number of members before the leader changes member status of 'Joining' members to 'Up'
You can tune the timeout after which downing decisions are made using the ``stable-after`` setting.

Note that you must not add more members to the cluster than **quorum-size * 2 - 1**, because then
both sides may down each other and thereby form two separate clusters. For example,
``quorum-size`` configured to 3 in a 6 node cluster may result in a split where each side
consists of 3 nodes each, i.e. each side thinks it has enough nodes to continue by
itself. A warning is logged if this recommendation is violated. ``static-quorum`` will 
never result in two separate clusters as long as you do not violate this rule.

If the cluster is split in 3 (or more) parts each part that is smaller than then configured ``quorum-size``
will down itself and possibly shutdown the whole cluster.

If more nodes than the configured ``quorum-size`` crash at the same time the other running nodes
will down themselves because they think that they are not in majority, and thereby the whole
cluster is terminated.

The decision can be based on nodes with a configured ``role`` instead of all nodes in the cluster.
This can be useful when some types of nodes are more valuable than others. You might for example
have some nodes responsible for persistent data and some nodes with stateless worker services.
Then it probably more important to keep as many persistent data nodes as possible even though
it means shutting down more worker nodes.

There is another use of the ``role`` as well. By defining a ``role`` for a few (e.g. 7) stable 
nodes in the cluster and using that in the configuration of ``static-quorum`` you will be able
to dynamically add and remove other nodes without this role and still have good decisions of what
nodes to keep running and what nodes to shut down in the case of network partitions. The advantage
of this approach compared to ``keep-majority`` (described below) is that you do not risk splitting
the cluster in two separate clusters. You must still obey the rule of not starting too many nodes
with this ``role`` as described above. It also suffers the risk of shutting down all nodes if there
is a failure when there are not enough number of nodes with this ``role`` remaining in the cluster, 
as described above.

Configuration::

    akka.cluster.split-brain-resolver.active-strategy=static-quorum

.. includecode:: ../../../akka-cluster/src/main/resources/reference.conf#static-quorum


Keep Majority
-------------

The strategy named ``keep-majority`` will down the unreachable nodes if the current node is in 
the majority part based on the last known membership information. Otherwise down the reachable nodes,
i.e. the own part. If the parts are of equal size the part containing the node with the lowest
address is kept.

This strategy is a good choice when the number of nodes in the cluster change dynamically and you can
therefore not use ``static-quorum``.

There is a small risk that the decision on both sides of the partition is not based on the same
information and therefore resulting in different decisions. This can happen when there are
membership changes at the same time as the network partition occurs. For example, the status of two
members are changed to ``Up`` on one side but that information is not disseminated to the other 
side before the connection is broken. Then one side sees two more nodes and both sides might consider 
themselves having majority, resulting in that each side downing the other side and thereby forming 
two separate clusters. It can also happen when some nodes crash after the network partition but
before the strategy has decided what to do.

In this regard it is more safe to use ``static-quorum``, but the advantages of the dynamic
nature of this strategy may outweigh the risk.   

Note that if there are more than two partitions and none is in majority each part will shut down
itself, terminating the whole cluster.

If more than half of the nodes crash at the same time the other running nodes will down themselves
because they think that they are not in majority, and thereby the whole cluster is terminated.  

The decision can be based on nodes with a configured ``role`` instead of all nodes in the cluster.
This can be useful when some types of nodes are more valuable than others. You might for example
have some nodes responsible for persistent data and some nodes with stateless worker services.
Then it probably more important to keep as many persistent data nodes as possible even though
it means shutting down more worker nodes.

Configuration::

    akka.cluster.split-brain-resolver.active-strategy=keep-majority

.. includecode:: ../../../akka-cluster/src/main/resources/reference.conf#keep-majority

Keep Oldest
-----------

The strategy named ``keep-oldest`` will down the part that does not contain the oldest 
member. The oldest member is interesting because the active Cluster Singleton instance
is running on the oldest member. 

There is one exception to this rule if ``down-if-alone`` is configured to ``on``.
Then, if the oldest node has partitioned from all other nodes the oldest will down itself
and keep all other nodes running. The strategy will not down the single oldest node when 
it is the only remaining node in the cluster.

Note that if the oldest node crashes the others will remove it from the cluster
when ``down-if-alone`` is ``on``, otherwise they will down themselves if the
oldest node crashes, i.e. shut down the whole cluster together with the oldest node.

This strategy is good to use if you use Cluster Singleton and do not want to shut down the node
where the singleton instance runs. If the oldest node crashes a new singleton instance will be 
started on the next oldest node. The drawback is that the strategy may keep only a few nodes 
in a large cluster. For example, if one part with the oldest consists of 2 nodes and the 
other part consists of 98 nodes then it will keep 2 nodes and shut down 98 nodes.

There is one risk with this strategy. If the different sides of a partition have different
opinions about which is the oldest node they may both shut down themselves or they may both
think that they should down the other side and continue running themselves. The latter results
in two separate clusters and two running singleton instances, one in each cluster. This can
happen in the rare event of the oldest node being removed from one side, but that information
has not been disseminated to the other side before the network partition happens. It can also
happen when the node crashes after the network partition but before the strategy has decided
what to do.

The decision can be based on nodes with a configured ``role`` instead of all nodes in the cluster,
i.e. using the oldest member (singleton) within the nodes with that role.

Configuration::

    akka.cluster.split-brain-resolver.active-strategy=keep-oldest

.. includecode:: ../../../akka-cluster/src/main/resources/reference.conf#keep-oldest

Keep Referee
------------

The strategy named ``keep-referee`` will down the part that does not contain the given 
referee node.

If the remaining number of nodes are less than the configured `down-all-if-less-than-nodes`
all nodes will be downed. If the referee node itself is removed all nodes will be downed.

This strategy is good if you have one node that hosts some critical resource and the
system cannot run without it. The drawback is that the referee node is a single point
of failure, by design. ``keep-referee`` will never result in two separate clusters.

Configuration::

    akka.cluster.split-brain-resolver.active-strategy=keep-referee

.. includecode:: ../../../akka-cluster/src/main/resources/reference.conf#keep-referee


Cluster Singleton and Cluster Sharding
======================================

The purpose of Cluster Singleton and Cluster Sharding is to run at most one instance
of a given actor at any point in time. When such an instance is shut down a new instance
is supposed to be started elsewhere in the cluster. It is important that the new instance is
not started before the old instance has been stopped. This is especially important when the
singleton or the sharded instance is persistent, since there must only be one active 
writer of the journaled events of a persistent actor instance.

Since the strategies on different sides of a network partition cannot communicate with each other
and they may take the decision at slightly different points in time there must be a time based
margin that makes sure that the new instance is not started before the old has been stopped.
This duration is configured with the following property:

.. includecode:: ../../../akka-cluster/src/main/resources/reference.conf#down-removal-margin

You would like to configure this to a short duration to have quick failover, but that will increase the
risk of having multiple singleton/sharded instances running at the same time and it may take different
amount of time to act on the decision (dissemination of the down/removal). It is recommended
to configure this to the same value as the ``stable-after`` property. Recommended minimum duration 
for different cluster sizes:

============ ===================
cluster size down-removal-margin
============ ===================
5            7 s
10           10 s
20           13 s
50           17 s
100          20 s
1000         30 s
============ ===================

Expected Failover Time
----------------------

As you have seen there are several configured timeouts that adds to the total failover latency.
With default configuration those are:

* failure detection 5 seconds
* stable-after 20 seconds
* down-removal-margin 20 seconds

In total you can expect the failover time of a singleton or sharded instance to be around 45 seconds
with default configuration. The default configuration is sized for a cluster of 100 nodes. If you have
around 10 nodes you can reduce the ``stable-after`` and ``down-removal-margin`` to around 10 seconds,
resulting in a expected failover time of around 25 seconds.   

