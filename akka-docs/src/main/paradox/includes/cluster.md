<!--- #cluster-singleton --->
### Cluster Singleton

For some use cases it is convenient or necessary to ensure only one 
actor of a certain type is running somewhere in the cluster.
This can be implemented by subscribing to member events, but there are several corner
cases to consider. Therefore, this specific use case is covered by the Cluster Singleton.

<!--- #cluster-singleton --->

<!--- #cluster-sharding --->
### Cluster Sharding

Distributes actors across several nodes in the cluster and supports interaction
with the actors using their logical identifier, but without having to care about
their physical location in the cluster.

<!--- #cluster-sharding --->

<!--- #cluster-ddata --->
### Distributed Data

*Akka Distributed Data* is useful when you need to share data between nodes in an
Akka Cluster. The data is accessed with an actor providing a key-value store like API.

<!--- #cluster-ddata --->
 
<!--- #cluster-pubsub --->
### Distributed Publish Subscribe

Publish-subscribe messaging between actors in the cluster, and point-to-point messaging
using the logical path of the actors, i.e. the sender does not have to know on which
node the destination actor is running.

<!--- #cluster-pubsub --->

<!--- #cluster-multidc --->
### Cluster across multiple data centers

Akka Cluster can be used across multiple data centers, availability zones or regions,
so that one Cluster can span multiple data centers and still be tolerant to network partitions.

<!--- #cluster-multidc --->

<!--- #join-seeds-programmatic --->
You may also join programmatically, which is attractive when dynamically discovering other nodes
at startup by using some external tool or API. When joining to seed nodes you should not include
the node itself except for the node that is supposed to be the first seed node, which should be
placed first in the parameter to the programmatic join.
<!--- #join-seeds-programmatic --->

<!--- #sharding-passivation-p1 --->
If the state of the entities are persistent you may stop entities that are not used to
reduce memory consumption. This is done by the application specific implementation of
the entity actors for example by defining receive timeout (`context.setReceiveTimeout`).
If a message is already enqueued to the entity when it stops itself the enqueued message
in the mailbox will be dropped.
<!--- #sharding-passivation-p1 --->

<!--- #sharding-passivation-p3 --->
will be sent back to the entity, which is then supposed to stop itself, otherwise it will
be stopped automatically. Incoming messages will be buffered by the `Shard` between reception
of `Passivate` and termination of the entity. Such buffered messages are thereafter delivered
to a new incarnation of the entity.
<!--- #sharding-passivation-p3 --->

<!--- #sharding-persistence-mode-deprecated --->
@@@ warning

Persistence for state store mode is deprecated. 

@@@
<!--- #sharding-persistence-mode-deprecated --->

<!--- #links-from-cluster-to-classic --->
@@@ note
For the Akka Classic documentation referenced in this guide see:

@ref:[Classic Cluster Sharding](../cluster-sharding.md) |
@ref:[Classic Distributed Data](../distributed-data.md) |
@ref:[Classic Cluster Singleton](../cluster-singleton.md) |
@ref:[Classic Persistence](../persistence.md)
@@@
<!--- #links-from-cluster-to-classic --->