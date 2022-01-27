# Cluster Specification

This document describes the design concepts of Akka Cluster. For the guide on using Akka Cluster please see either

* @ref:[Cluster Usage](../typed/cluster.md)
* @ref:[Cluster Usage with classic Akka APIs](../cluster-usage.md)
* @ref:[Cluster Membership Service](cluster-membership.md)
 
## Introduction

Akka Cluster provides a fault-tolerant decentralized peer-to-peer based
@ref:[Cluster Membership Service](cluster-membership.md#cluster-membership-service) with no single point of failure or 
single point of bottleneck. It does this using @ref:[gossip](#gossip) protocols and an automatic [failure detector](#failure-detector).

Akka Cluster allows for building distributed applications, where one application or service spans multiple nodes
(in practice multiple @apidoc[typed.ActorSystem]s). 

## Terms

**node**
: A logical member of a cluster. There could be multiple nodes on a physical
machine. Defined by a *hostname:port:uid* tuple.

**cluster**
: A set of nodes joined together through the @ref:[Cluster Membership Service](cluster-membership.md#cluster-membership-service).

**leader**
: A single node in the cluster that acts as the leader. Managing cluster convergence
and membership state transitions.

### Gossip

The cluster membership used in Akka is based on Amazon's [Dynamo](https://www.allthingsdistributed.com/files/amazon-dynamo-sosp2007.pdf) system and
particularly the approach taken in Basho's' [Riak](https://en.wikipedia.org/wiki/Riak) distributed database.
Cluster membership is communicated using a [Gossip Protocol](https://en.wikipedia.org/wiki/Gossip_protocol), where the current
state of the cluster is gossiped randomly through the cluster, with preference to
members that have not seen the latest version.

#### Vector Clocks

[Vector clocks](https://en.wikipedia.org/wiki/Vector_clock) are a type of data structure and algorithm for generating a partial
ordering of events in a distributed system and detecting causality violations.

We use vector clocks to reconcile and merge differences in cluster state
during gossiping. A vector clock is a set of (node, counter) pairs. Each update
to the cluster state has an accompanying update to the vector clock.

#### Gossip Convergence

Information about the cluster converges locally at a node at certain points in time.
This is when a node can prove that the cluster state it is observing has been observed
by all other nodes in the cluster. Convergence is implemented by passing a set of nodes
that have seen current state version during gossip. This information is referred to as the
seen set in the gossip overview. When all nodes are included in the seen set there is
convergence.

Gossip convergence cannot occur while any nodes are `unreachable`. The nodes need
to become `reachable` again, or moved to the `down` and `removed` states
(see the @ref:[Cluster Membership Lifecycle](cluster-membership.md#membership-lifecycle) section). This only blocks the leader
from performing its cluster membership management and does not influence the application
running on top of the cluster. For example this means that during a network partition
it is not possible to add more nodes to the cluster. The nodes can join, but they
will not be moved to the `up` state until the partition has healed or the unreachable
nodes have been downed.

#### Failure Detector

The failure detector in Akka Cluster is responsible for trying to detect if a node is
`unreachable` from the rest of the cluster. For this we are using the
@ref:[Phi Accrual Failure Detector](failure-detector.md) implementation.
To be able to survive sudden abnormalities, such as garbage collection pauses and
transient network failures the failure detector is easily @ref:[configurable](cluster.md#using-the-failure-detector)
for tuning to your environments and needs.

In a cluster each node is monitored by a few (default maximum 5) other nodes.
The nodes to monitor are selected from neighbors in a hashed ordered node ring.
This is to increase the likelihood to monitor across racks and data centers, but the order
is the same on all nodes, which ensures full coverage.
  
When any node is detected to be `unreachable` this data is spread to
the rest of the cluster through the @ref:[gossip](#gossip). In other words, only one node needs to
mark a node `unreachable` to have the rest of the cluster mark that node `unreachable`.
 
The failure detector will also detect if the node becomes `reachable` again. When
all nodes that monitored the `unreachable` node detect it as `reachable` again
the cluster, after gossip dissemination, will consider it as `reachable`.

<a id="quarantined"></a>
If system messages cannot be delivered to a node it will be quarantined and then it
cannot come back from `unreachable`. This can happen if the there are too many
unacknowledged system messages (e.g. watch, Terminated, remote actor deployment,
failures of actors supervised by remote parent). Then the node needs to be moved
to the `down` or `removed` states (see @ref:[Cluster Membership Lifecycle](cluster-membership.md#membership-lifecycle))
and the actor system of the quarantined node must be restarted before it can join the cluster again.

See the following for more details:
 
* @ref:[Phi Accrual Failure Detector](failure-detector.md) implementation
* @ref:[Using the Failure Detector](cluster.md#using-the-failure-detector)
 
#### Leader

After gossip convergence a `leader` for the cluster can be determined. There is no
`leader` election process, the `leader` can always be recognised deterministically
by any node whenever there is gossip convergence. The leader is only a role, any node
can be the leader and it can change between convergence rounds.
The `leader` is the first node in sorted order that is able to take the leadership role,
where the preferred member states for a `leader` are `up` and `leaving`
(see the @ref:[Cluster Membership Lifecycle](cluster-membership.md#membership-lifecycle) for more  information about member states).

The role of the `leader` is to shift members in and out of the cluster, changing
`joining` members to the `up` state or `exiting` members to the `removed`
state. Currently `leader` actions are only triggered by receiving a new cluster
state with gossip convergence.

#### Seed Nodes

The seed nodes are contact points for new nodes joining the cluster.
When a new node is started it sends a message to all seed nodes and then sends
a join command to the seed node that answers first.

The seed nodes configuration value does not have any influence on the running
cluster itself, it is only relevant for new nodes joining the cluster as it
helps them to find contact points to send the join command to; a new member
can send this command to any current member of the cluster, not only to the seed nodes.

#### Gossip Protocol

A variation of *push-pull gossip* is used to reduce the amount of gossip
information sent around the cluster. In push-pull gossip a digest is sent
representing current versions but not actual values; the recipient of the gossip
can then send back any values for which it has newer versions and also request
values for which it has outdated versions. Akka uses a single shared state with
a vector clock for versioning, so the variant of push-pull gossip used in Akka
makes use of this version to only push the actual state as needed.

Periodically, the default is every 1 second, each node chooses another random
node to initiate a round of gossip with. If less than ½ of the nodes resides in the
seen set (have seen the new state) then the cluster gossips 3 times instead of once
every second. This adjusted gossip interval is a way to speed up the convergence process
in the early dissemination phase after a state change.

The choice of node to gossip with is random but biased towards nodes that might not have seen
the current state version. During each round of gossip exchange, when convergence is not yet reached, a node
uses a very high probability (which is configurable) to gossip with another node which is not part of the seen set, i.e. 
which is likely to have an older version of the state. Otherwise it gossips with any random live node.

This biased selection is a way to speed up the convergence process in the late dissemination
phase after a state change.

For clusters larger than 400 nodes (configurable, and suggested by empirical evidence)
the 0.8 probability is gradually reduced to avoid overwhelming single stragglers with
too many concurrent gossip requests. The gossip receiver also has a mechanism to
protect itself from too many simultaneous gossip messages by dropping messages that
have been enqueued in the mailbox for too long of a time.

While the cluster is in a converged state the gossiper only sends a small gossip status message containing the gossip
version to the chosen node. As soon as there is a change to the cluster (meaning non-convergence)
then it goes back to biased gossip again.

The recipient of the gossip state or the gossip status can use the gossip version
(vector clock) to determine whether:

 1. it has a newer version of the gossip state, in which case it sends that back
to the gossiper
 2. it has an outdated version of the state, in which case the recipient requests
the current state from the gossiper by sending back its version of the gossip state
 3. it has conflicting gossip versions, in which case the different versions are merged
and sent back

If the recipient and the gossip have the same version then the gossip state is
not sent or requested.

The periodic nature of the gossip has a nice batching effect of state changes,
e.g. joining several nodes quickly after each other to one node will result in only
one state change to be spread to other members in the cluster.

The gossip messages are serialized with [protobuf](https://github.com/protocolbuffers/protobuf) and also gzipped to reduce payload
size.
