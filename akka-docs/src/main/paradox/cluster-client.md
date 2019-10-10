# Classic Cluster Client

@@@ warning

Cluster Client is deprecated in favor of using [Akka gRPC](https://doc.akka.io/docs/akka-grpc/current/index.html).
It is not advised to build new applications with Cluster Client, and existing users @ref[should migrate](#migration-to-akka-grpc).

@@@

@@include[includes.md](includes.md) { #actor-api }

@@project-info{ projectId="akka-cluster-tools" }

## Dependency

To use Cluster Client, you must add the following dependency in your project:

@@dependency[sbt,Maven,Gradle] {
  group=com.typesafe.akka
  artifact=akka-cluster-tools_$scala.binary_version$
  version=$akka.version$
}

## Introduction

An actor system that is not part of the cluster can communicate with actors
somewhere in the cluster via the @apidoc[ClusterClient], the client can run in an `ActorSystem` that is part of
another cluster. It only needs to know the location of one (or more) nodes to use as initial
contact points. It will establish a connection to a @apidoc[akka.cluster.client.ClusterReceptionist] somewhere in
the cluster. It will monitor the connection to the receptionist and establish a new
connection if the link goes down. When looking for a new receptionist it uses fresh
contact points retrieved from previous establishment, or periodically refreshed contacts,
i.e. not necessarily the initial contact points.

Using the @apidoc[ClusterClient] for communicating with a cluster from the outside requires that the system with the client
can both connect and be connected to with Akka Remoting from all the nodes in the cluster with a receptionist.
This creates a tight coupling in that the client and cluster systems may need to have the same version of 
both Akka, libraries, message classes, serializers and potentially even the JVM. In many cases it is a better solution 
to use a more explicit and decoupling protocol such as [HTTP](https://doc.akka.io/docs/akka-http/current/index.html) or 
[gRPC](https://doc.akka.io/docs/akka-grpc/current/).

Additionally since Akka Remoting is primarily designed as a protocol for Akka Cluster there is no explicit resource
management, when a @apidoc[ClusterClient] has been used it will cause connections with the cluster until the ActorSystem is 
stopped (unlike other kinds of network clients). 

@apidoc[ClusterClient] should not be used when sending messages to actors that run
within the same cluster. Similar functionality as the @apidoc[ClusterClient] is
provided in a more efficient way by @ref:[Distributed Publish Subscribe in Cluster](distributed-pub-sub.md) for actors that
belong to the same cluster.

It is necessary that the connecting system has its `akka.actor.provider` set  to `remote` or `cluster` when using
the cluster client.

The receptionist is supposed to be started on all nodes, or all nodes with specified role,
in the cluster. The receptionist can be started with the @apidoc[akka.cluster.client.ClusterReceptionist] extension
or as an ordinary actor.

You can send messages via the @apidoc[ClusterClient] to any actor in the cluster that is registered
in the @apidoc[DistributedPubSubMediator] used by the @apidoc[akka.cluster.client.ClusterReceptionist].
The @apidoc[ClusterClientReceptionist] provides methods for registration of actors that
should be reachable from the client. Messages are wrapped in `ClusterClient.Send`,
@scala[@scaladoc[`ClusterClient.SendToAll`](akka.cluster.client.ClusterClient$)]@java[`ClusterClient.SendToAll`] or @scala[@scaladoc[`ClusterClient.Publish`](akka.cluster.client.ClusterClient$)]@java[`ClusterClient.Publish`].

Both the @apidoc[ClusterClient] and the @apidoc[ClusterClientReceptionist] emit events that can be subscribed to.
The @apidoc[ClusterClient] sends out notifications in relation to having received a list of contact points
from the @apidoc[ClusterClientReceptionist]. One use of this list might be for the client to record its
contact points. A client that is restarted could then use this information to supersede any previously
configured contact points.

The @apidoc[ClusterClientReceptionist] sends out notifications in relation to having received a contact
from a @apidoc[ClusterClient]. This notification enables the server containing the receptionist to become aware of
what clients are connected.

1. **ClusterClient.Send**

    The message will be delivered to one recipient with a matching path, if any such
    exists. If several entries match the path the message will be delivered
    to one random destination. The sender of the message can specify that local
    affinity is preferred, i.e. the message is sent to an actor in the same local actor
    system as the used receptionist actor, if any such exists, otherwise random to any other
    matching entry.

2. **ClusterClient.SendToAll**

    The message will be delivered to all recipients with a matching path.

3. **ClusterClient.Publish**

    The message will be delivered to all recipients Actors that have been registered as subscribers
    to the named topic.

Response messages from the destination actor are tunneled via the receptionist
to avoid inbound connections from other cluster nodes to the client:

* @scala[@scaladoc[`sender()`](akka.actor.Actor)]@java[@javadoc[`getSender()`](akka.actor.Actor)], as seen by the destination actor, is not the client itself,
  but the receptionist
* @scala[@scaladoc[`sender()`](akka.actor.Actor)] @java[@javadoc[`getSender()`](akka.actor.Actor)] of the response messages, sent back from the destination and seen by the client,
  is `deadLetters`
  
since the client should normally send subsequent messages via the @apidoc[ClusterClient].
It is possible to pass the original sender inside the reply messages if
the client is supposed to communicate directly to the actor in the cluster.

While establishing a connection to a receptionist the @apidoc[ClusterClient] will buffer
messages and send them when the connection is established. If the buffer is full
the @apidoc[ClusterClient] will drop old messages when new messages are sent via the client.
The size of the buffer is configurable and it can be disabled by using a buffer size of 0.

It's worth noting that messages can always be lost because of the distributed nature
of these actors. As always, additional logic should be implemented in the destination
(acknowledgement) and in the client (retry) actors to ensure at-least-once message delivery.

## An Example

On the cluster nodes first start the receptionist. Note, it is recommended to load the extension
when the actor system is started by defining it in the `akka.extensions` configuration property:

```
akka.extensions = ["akka.cluster.client.ClusterClientReceptionist"]
```

Next, register the actors that should be available for the client.

Scala
:  @@snip [ClusterClientSpec.scala](/akka-cluster-tools/src/multi-jvm/scala/akka/cluster/client/ClusterClientSpec.scala) { #server }

Java
:  @@snip [ClusterClientTest.java](/akka-cluster-tools/src/test/java/akka/cluster/client/ClusterClientTest.java) { #server }

On the client you create the @apidoc[ClusterClient] actor and use it as a gateway for sending
messages to the actors identified by their path (without address information) somewhere
in the cluster.

Scala
:  @@snip [ClusterClientSpec.scala](/akka-cluster-tools/src/multi-jvm/scala/akka/cluster/client/ClusterClientSpec.scala) { #client }

Java
:  @@snip [ClusterClientTest.java](/akka-cluster-tools/src/test/java/akka/cluster/client/ClusterClientTest.java) { #client }

The `initialContacts` parameter is a @scala[`Set[ActorPath]`]@java[`Set<ActorPath>`], which can be created like this:

Scala
:  @@snip [ClusterClientSpec.scala](/akka-cluster-tools/src/multi-jvm/scala/akka/cluster/client/ClusterClientSpec.scala) { #initialContacts }

Java
:  @@snip [ClusterClientTest.java](/akka-cluster-tools/src/test/java/akka/cluster/client/ClusterClientTest.java) { #initialContacts }

You will probably define the address information of the initial contact points in configuration or system property.
See also @ref:[Configuration](#cluster-client-config).

A more comprehensive sample is available in the tutorial named
@scala[[Distributed workers with Akka and Scala](https://github.com/typesafehub/activator-akka-distributed-workers).]
@java[[Distributed workers with Akka and Java](https://github.com/typesafehub/activator-akka-distributed-workers-java).]

## ClusterClientReceptionist Extension

In the example above the receptionist is started and accessed with the `akka.cluster.client.ClusterClientReceptionist` extension.
That is convenient and perfectly fine in most cases, but it can be good to know that it is possible to
start the `akka.cluster.client.ClusterReceptionist` actor as an ordinary actor and you can have several
different receptionists at the same time, serving different types of clients.

Note that the @apidoc[ClusterClientReceptionist] uses the @apidoc[DistributedPubSub] extension, which is described
in @ref:[Distributed Publish Subscribe in Cluster](distributed-pub-sub.md).

It is recommended to load the extension when the actor system is started by defining it in the
`akka.extensions` configuration property:

```
akka.extensions = ["akka.cluster.client.ClusterClientReceptionist"]
```

## Events

As mentioned earlier, both the @apidoc[ClusterClient] and @apidoc[ClusterClientReceptionist] emit events that can be subscribed to.
The following code snippet declares an actor that will receive notifications on contact points (addresses to the available
receptionists), as they become available. The code illustrates subscribing to the events and receiving the @apidoc[ClusterClient]
initial state.

Scala
:  @@snip [ClusterClientSpec.scala](/akka-cluster-tools/src/multi-jvm/scala/akka/cluster/client/ClusterClientSpec.scala) { #clientEventsListener }

Java
:  @@snip [ClusterClientTest.java](/akka-cluster-tools/src/test/java/akka/cluster/client/ClusterClientTest.java) { #clientEventsListener }

Similarly we can have an actor that behaves in a similar fashion for learning what cluster clients are connected to a @apidoc[ClusterClientReceptionist]:

Scala
:  @@snip [ClusterClientSpec.scala](/akka-cluster-tools/src/multi-jvm/scala/akka/cluster/client/ClusterClientSpec.scala) { #receptionistEventsListener }

Java
:  @@snip [ClusterClientTest.java](/akka-cluster-tools/src/test/java/akka/cluster/client/ClusterClientTest.java) { #receptionistEventsListener }

<a id="cluster-client-config"></a>
## Configuration

The @apidoc[ClusterClientReceptionist] extension (or @apidoc[akka.cluster.client.ClusterReceptionistSettings]) can be configured
with the following properties:

@@snip [reference.conf](/akka-cluster-tools/src/main/resources/reference.conf) { #receptionist-ext-config }

The following configuration properties are read by the @apidoc[ClusterClientSettings]
when created with a @scala[@scaladoc[`ActorSystem`](akka.actor.ActorSystem)]@java[@javadoc[`ActorSystem`](akka.actor.ActorSystem)] parameter. It is also possible to amend the @apidoc[ClusterClientSettings]
or create it from another config section with the same layout as below. @apidoc[ClusterClientSettings] is
a parameter to the @scala[@scaladoc[`ClusterClient.props`](akka.cluster.client.ClusterClient$)]@java[@javadoc[`ClusterClient.props`](akka.cluster.client.ClusterClient$)] factory method, i.e. each client can be configured
with different settings if needed.

@@snip [reference.conf](/akka-cluster-tools/src/main/resources/reference.conf) { #cluster-client-config }

## Failure handling

When the cluster client is started it must be provided with a list of initial contacts which are cluster
nodes where receptionists are running. It will then repeatedly (with an interval configurable
by `establishing-get-contacts-interval`) try to contact those until it gets in contact with one of them.
While running, the list of contacts are continuously updated with data from the receptionists (again, with an
interval configurable with `refresh-contacts-interval`), so that if there are more receptionists in the cluster
than the initial contacts provided to the client the client will learn about them.

While the client is running it will detect failures in its connection to the receptionist by heartbeats
if more than a configurable amount of heartbeats are missed the client will try to reconnect to its known
set of contacts to find a receptionist it can access.

## When the cluster cannot be reached at all

It is possible to make the cluster client stop entirely if it cannot find a receptionist it can talk to
within a configurable interval. This is configured with the `reconnect-timeout`, which defaults to `off`.
This can be useful when initial contacts are provided from some kind of service registry, cluster node addresses
are entirely dynamic and the entire cluster might shut down or crash, be restarted on new addresses. Since the
client will be stopped in that case a monitoring actor can watch it and upon `Terminate` a new set of initial
contacts can be fetched and a new cluster client started.

## Migration to Akka gRPC

Cluster Client is deprecated and it is not advised to build new applications with it.
As a replacement we recommend using [Akka gRPC](https://doc.akka.io/docs/akka-grpc/current/index.html)
with an application-specific protocol. The benefits of this approach are:

* Improved security by using TLS for gRPC (HTTP/2) versus exposing Akka Remoting outside the Akka Cluster
* Easier to update clients and servers independent of each other
* Improved protocol definition between client and server
* Usage of [Akka gRPC Service Discovery](https://doc.akka.io/docs/akka-grpc/current/client/configuration.html#using-akka-discovery-for-endpoint-discovery)
* Clients do not need to use Akka
* See also [gRPC versus Akka Remoting](https://doc.akka.io/docs/akka-grpc/current/whygrpc.html#grpc-vs-akka-remoting)

### Migrating directly

Existing users of Cluster Client may migrate directly to Akka gRPC and use it
as documented in [its documentation](https://doc.akka.io/docs/akka-grpc/current).

### Migrating gradually

If your application extensively uses Cluster Client, a more gradual migration
might be desired that requires less re-write of the application. That migration step is described in this section. We recommend migration directly if feasible,
though.

An example is provided to illustrate an approach to migrate from the deprecated Cluster Client to Akka gRPC,
with minimal changes to your existing code. The example is intended to be copied and adjusted to your needs.
It will not be provided as a published artifact.

* [akka-samples/akka-sample-cluster-cluster-client-grpc-scala](https://github.com/akka/akka-samples/tree/2.6/akka-sample-cluster-client-grpc-scala) implemented in Scala
* [akka-samples/akka-sample-cluster-cluster-client-grpc-java](https://github.com/akka/akka-samples/tree/2.6/akka-sample-cluster-client-grpc-java) implemented in Java

The example is still using an actor on the client side to have an API that is very close
to the original Cluster Client. The messages this actor can handle correspond to the
@ref:[Distributed Pub Sub](distributed-pub-sub.md) messages on the server side, such as
`ClusterClient.Send` and `ClusterClient.Publish`.

The `ClusterClient` actor delegates those messages to the gRPC client, and on the
server side those are translated and delegated to the destination actors that
are registered via the `ClusterClientReceptionist` in the same way as in the original.

Akka gRPC is used as the transport for the messages between client and server, instead of Akka Remoting.

The application specific messages are wrapped and serialized with Akka Serialization,
which means that care must be taken to keep wire compatibility when changing any messages used
between the client and server. The Akka configuration of Akka serializers must be the same (or
being compatible) on client and server.

#### Next steps

After this first migration step from Cluster Client to Akka gRPC, you can start
replacing calls to `ClusterClientReceptionistService` with new,
application-specific gRPC endpoints.

#### Differences

Aside from the underlying implementation using gRPC instead of Actor messages
and Akka Remoting it's worth pointing out the following differences between
the Cluster Client and the example emulating Cluster Client with Akka gRPC as
transport.

##### Single request-reply

For request-reply interactions when there is only one reply message for each request
it is more efficient to use the `ClusterClient.AskSend` message instead of
`ClusterClient.Send` as illustrated in the example. Then it doesn't have to
setup a full bidirectional gRPC stream for each request but can use the @scala[`Future`]@java[`CompletionStage`]
based API.

##### Initial contact points

Instead of configured initial contact points the [Akka gRPC Service Discovery](https://doc.akka.io/docs/akka-grpc/current/client/configuration.html#using-akka-discovery-for-endpoint-discovery) can be used.

##### Failure detection

Heartbeat messages and failure detection of the connections have been removed
since that should be handled by the gRPC connections.
