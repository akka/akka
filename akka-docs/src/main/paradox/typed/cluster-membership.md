---
project.description: The Akka Cluster node membership service, manages dynamic member states and lifecycle with no external infrastructure needed.
---
# Cluster Membership Service

The core of Akka Cluster is the cluster membership, to keep track of what nodes are part of the cluster and
their health. Cluster membership is communicated using @ref:[gossip](cluster-concepts.md#gossip) and
@ref:[failure detection](cluster-concepts.md#failure-detector).

There are several @ref:[Higher level Cluster tools](../typed/cluster.md#higher-level-cluster-tools) that are built
on top of the cluster membership service.

## Introduction

A cluster is made up of a set of member nodes. The identifier for each node is a
`hostname:port:uid` tuple. An Akka application can be distributed over a cluster with
each node hosting some part of the application. Cluster membership and the actors running
on that node of the application are decoupled. A node could be a member of a
cluster without hosting any actors. Joining a cluster is initiated
by issuing a `Join` command to one of the nodes in the cluster to join.

The node identifier internally also contains a UID that uniquely identifies this
actor system instance at that `hostname:port`. Akka uses the UID to be able to
reliably trigger remote death watch. This means that the same actor system can never
join a cluster again once it's been removed from that cluster. To re-join an actor
system with the same `hostname:port` to a cluster you have to stop the actor system
and start a new one with the same `hostname:port` which will then receive a different
UID.
 
## Member States

The cluster membership state is a specialized [CRDT](http://hal.upmc.fr/docs/00/55/55/88/PDF/techreport.pdf), which means that it has a monotonic
merge function. When concurrent changes occur on different nodes the updates can always be
merged and converge to the same end result.

 * **joining** - transient state when joining a cluster
   
 * **weakly up** - transient state while network split (only if `akka.cluster.allow-weakly-up-members=on`)
   
 * **up** - normal operating state
   
 * **leaving** / **exiting** - states during graceful removal
   
 * **down** - marked as down (no longer part of cluster decisions)
   
 * **removed** - tombstone state (no longer a member)

## Member Events

The events to track the life-cycle of members are:

 * `ClusterEvent.MemberJoined` - A new member has joined the cluster and its status has been changed to `Joining`
 * `ClusterEvent.MemberUp` - A new member has joined the cluster and its status has been changed to `Up`
 * `ClusterEvent.MemberExited` - A member is leaving the cluster and its status has been changed to `Exiting`
Note that the node might already have been shutdown when this event is published on another node.
 * `ClusterEvent.MemberRemoved` - Member completely removed from the cluster.
 * `ClusterEvent.UnreachableMember` - A member is considered as unreachable, detected by the failure detector
of at least one other node.
 * `ClusterEvent.ReachableMember` - A member is considered as reachable again, after having been unreachable.
All nodes that previously detected it as unreachable has detected it as reachable again.
 
## Membership Lifecycle

A node begins in the `joining` state. Once all nodes have seen that the new
node is joining (through gossip convergence) the `leader` will set the member
state to `up`.

If a node is leaving the cluster in a safe, expected manner then it switches to
the `leaving` state. Once the leader sees the convergence on the node in the
`leaving` state, the leader will then move it to `exiting`.  Once all nodes
have seen the exiting state (convergence) the `leader` will remove the node
from the cluster, marking it as `removed`.

If a node is `unreachable` then gossip convergence is not possible and therefore
any `leader` actions are also not possible (for instance, allowing a node to
become a part of the cluster). To be able to move forward the state of the
`unreachable` nodes must be changed. It must become `reachable` again or marked
as `down`. If the node is to join the cluster again the actor system must be
restarted and go through the joining process again. If new incarnation of the unreachable
node tries to rejoin the cluster old incarnation will be marked as `down` and new
incarnation can rejoin the cluster without manual intervention. 

<a id="weakly-up"></a>
## WeaklyUp Members

If a node is `unreachable` then gossip convergence is not possible and therefore any
`leader` actions are also not possible. However, we still might want new nodes to join
the cluster in this scenario.

`Joining` members will be promoted to `WeaklyUp` and become part of the cluster if
convergence can't be reached. Once gossip convergence is reached, the leader will move `WeaklyUp`
members to `Up`.

This feature is enabled by default, but it can be disabled with configuration option:

```
akka.cluster.allow-weakly-up-members = off
```

You can subscribe to the `WeaklyUp` membership event to make use of the members that are
in this state, but you should be aware of that members on the other side of a network partition
have no knowledge about the existence of the new members. You should for example not count
`WeaklyUp` members in quorum decisions.

As mentioned before, if a node is `unreachable` then gossip convergence is not
possible and therefore any `leader` actions are also not possible. By enabling
`akka.cluster.allow-weakly-up-members` (enabled by default) it is possible to 
let new joining nodes be promoted while convergence is not yet reached. These 
`Joining` nodes will be promoted as `WeaklyUp`. Once gossip convergence is 
reached, the leader will move `WeaklyUp` members to `Up`.

Note that members on the other side of a network partition have no knowledge about 
the existence of the new members. You should for example not count `WeaklyUp` 
members in quorum decisions.

## State Diagrams

### State Diagram for the Member States (`akka.cluster.allow-weakly-up-members=off`)

![member-states.png](../images/member-states.png)

### State Diagram for the Member States (`akka.cluster.allow-weakly-up-members=on`)

![member-states-weakly-up.png](../images/member-states-weakly-up.png)
  
#### User Actions

 * **join** - join a single node to a cluster - can be explicit or automatic on
startup if a node to join have been specified in the configuration
   
 * **leave** - tell a node to leave the cluster gracefully
   
 * **down** - mark a node as down
   
#### Leader Actions

The `leader` has the following duties:

 * shifting members in and out of the cluster
    * joining -> up
    * weakly up -> up *(no convergence is required for this leader action to be performed)*
    * exiting -> removed

#### Failure Detection and Unreachability

 * **fd*** - the failure detector of one of the monitoring nodes has triggered
causing the monitored node to be marked as unreachable
   
 * **unreachable*** - unreachable is not a real member states but more of a flag in addition to the state signaling that the cluster is unable to talk to this node, after being unreachable the failure detector may detect it as reachable again and thereby remove the flag
