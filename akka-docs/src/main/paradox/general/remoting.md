# Location Transparency

The previous section describes how actor paths are used to enable location
transparency. This special feature deserves some extra explanation, because the
related term “transparent remoting” was used quite differently in the context
of programming languages, platforms and technologies.

## Distributed by Default

Everything in Akka is designed to work in a distributed setting: all
interactions of actors use purely message passing and everything is
asynchronous. This effort has been undertaken to ensure that all functions are
available equally when running within a single JVM or on a cluster of hundreds
of machines. The key for enabling this is to go from remote to local by way of
optimization instead of trying to go from local to remote by way of
generalization. See [this classic paper](https://doc.akka.io/docs/misc/smli_tr-94-29.pdf)
for a detailed discussion on why the second approach is bound to fail.

## Ways in which Transparency is Broken

What is true of Akka need not be true of the application which uses it, since
designing for distributed execution poses some restrictions on what is
possible. The most obvious one is that all messages sent over the wire must be
serializable.

Another consequence is that everything needs to be aware of all interactions
being fully asynchronous, which in a computer network might mean that it may
take several minutes for a message to reach its recipient (depending on
configuration). It also means that the probability for a message to be lost is
much higher than within one JVM, where it is close to zero (still: no hard
guarantee!).

<a id="symmetric-communication"></a>
## Peer-to-Peer vs. Client-Server

Akka Remoting is a communication module for connecting actor systems in a peer-to-peer fashion,
and it is the foundation for Akka Clustering. The design of remoting is driven by two (related)
design decisions:

 1. Communication between involved systems is symmetric: if a system A can connect to a system B
then system B must also be able to connect to system A independently.
 2. The role of the communicating systems are symmetric in regards to connection patterns: there
is no system that only accepts connections, and there is no system that only initiates connections.

The consequence of these decisions is that it is not possible to safely create
pure client-server setups with predefined roles (violates assumption 2).
For client-server setups it is better to use HTTP or Akka I/O.

**Important**: Using setups involving Network Address Translation, Load Balancers or Docker
containers violates assumption 1, unless additional steps are taken in the
network configuration to allow symmetric communication between involved systems.
In such situations Akka can be configured to bind to a different network
address than the one used for establishing connections between Akka nodes.
See @ref:[Akka behind NAT or in a Docker container](../remoting-artery.md#remote-configuration-nat-artery).

## Marking Points for Scaling Up with Routers

In addition to being able to run different parts of an actor system on
different nodes of a cluster, it is also possible to scale up onto more cores
by multiplying actor sub-trees which support parallelization (think for example
a search engine processing different queries in parallel). The clones can then
be routed to in different fashions, e.g. round-robin. See @ref:[Routing](../typed/routers.md) for more details.
