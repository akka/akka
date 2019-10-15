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

Distributed Data is useful when you need to share data between nodes in an
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

<!--- #sharding-persistence-mode-deprecated --->
@@@ warning

Persistence for state store mode is deprecated. 

@@@
<!--- #sharding-persistence-mode-deprecated --->
