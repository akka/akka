# Actor discovery

For the Akka Classic documentation of this feature see @ref:[Classic Actors](../actors.md#actorselection).

## Dependency

To use Akka Actor Typed, you must add the following dependency in your project:

@@dependency[sbt,Maven,Gradle] {
  group=com.typesafe.akka
  artifact=akka-actor-typed_$scala.binary_version$
  version=$akka.version$
}

## Obtaining Actor references

In order for an actor to send a message to one or more other actors it needs to obtain an @ref:[Actor reference](../general/addressing.md#what-is-an-actor-reference). 
This reference relates to the notion of locality.
In his 2010 paper, [Actor Model of Computation: Scalable Robust Information Systems](https://arxiv.org/abs/1008.1459), 
Carl Hewitt relays locality and message processing as, 
“an Actor can send messages only to addresses for which it has information by the following means: 
1. that it receives in the message 
2. that it already had before it received the message 
3. that it creates while processing the message. “

Similarly, Akka has three general ways to obtain Actor references, but also adds a discovery method
1. By @ref:[creating actors](actor-lifecycle.md#creating-actors) 
2. Passed as constructor parameters to an actor on its creation
3. Passed as parameters of a message received by an actor
4. By discovery using the @ref:[Receptionist](#receptionist), which is useful if you need something to bootstrap the interaction. 
For example when they need to be discoverable by actors running either on different nodes in the Cluster, 
or if the above three general methods are not applicable.

## Receptionist

When an actor needs to be discovered by another actor but you are unable to put a reference to it in an incoming message,
you can use the `Receptionist`. You register the specific actors that should be discoverable 
from other nodes in the local `Receptionist` instance. The API of the receptionist is also based on actor messages. 
This registry of actor references is then automatically distributed to all other nodes in the cluster. 
You can lookup such actors with the key that was used when they were registered. The reply to such a `Find` request is 
a `Listing`, which contains a `Set` of actor references that are registered for the key. Note that several actors can be 
registered to the same key.

The registry is dynamic. New actors can be registered during the lifecycle of the system. Entries are removed when 
registered actors are stopped, manually deregistered or the node they live on is removed from the @ref:[Cluster](cluster.md). 
To facilitate this dynamic aspect you can also subscribe to changes with the `Receptionist.Subscribe` message. It will send 
`Listing` messages to the subscriber when entries for a key are changed.

These imports are used in the following example:

Scala
:  @@snip [ReceptionistExample](/akka-cluster-typed/src/test/scala/docs/akka/cluster/typed/ReceptionistExample.scala) { #import }

Java
:  @@snip [ReceptionistExample](/akka-cluster-typed/src/test/java/jdocs/akka/cluster/typed/ReceptionistExample.java) { #import }

First we create a `PingService` actor and register it with the `Receptionist` against a
`ServiceKey` that will later be used to lookup the reference:

Scala
:  @@snip [ReceptionistExample](/akka-cluster-typed/src/test/scala/docs/akka/cluster/typed/ReceptionistExample.scala) { #ping-service }

Java
:  @@snip [ReceptionistExample](/akka-cluster-typed/src/test/java/jdocs/akka/cluster/typed/ReceptionistExample.java) { #ping-service }

Then we have another actor that requires a `PingService` to be constructed:

Scala
:  @@snip [ReceptionistExample](/akka-cluster-typed/src/test/scala/docs/akka/cluster/typed/ReceptionistExample.scala) { #pinger }

Java
:  @@snip [ReceptionistExample](/akka-cluster-typed/src/test/java/jdocs/akka/cluster/typed/ReceptionistExample.java) { #pinger }

Finally in the guardian actor we spawn the service as well as subscribing to any actors registering
against the `ServiceKey`. Subscribing means that the guardian actor will be informed of any
new registrations via a `Listing` message:

Scala
:  @@snip [ReceptionistExample](/akka-cluster-typed/src/test/scala/docs/akka/cluster/typed/ReceptionistExample.scala) { #pinger-guardian }

Java
:  @@snip [ReceptionistExample](/akka-cluster-typed/src/test/java/jdocs/akka/cluster/typed/ReceptionistExample.java) { #pinger-guardian }

Each time a new (which is just a single time in this example) `PingService` is registered the
guardian actor spawns a `Pinger` for each currently known `PingService`. The `Pinger`
sends a `Ping` message and when receiving the `Pong` reply it stops.

In above example we used `Receptionist.Subscribe`, but it's also possible to request a single `Listing`
of the current state without receiving further updates by sending the `Receptionist.Find` message to the
receptionist. An example of using `Receptionist.Find`:

Scala
:  @@snip [ReceptionistExample](/akka-cluster-typed/src/test/scala/docs/akka/cluster/typed/ReceptionistExample.scala) { #find }

Java
:  @@snip [ReceptionistExample](/akka-cluster-typed/src/test/java/jdocs/akka/cluster/typed/ReceptionistExample.java) { #find }

Also note how a `messageAdapter` is used to convert the `Receptionist.Listing` to a message type that
the `PingManager` understands.

If a server no longer wish to be associated with a service key it can deregister using the command `Receptionist.Deregister`
which will remove the association and inform all subscribers.

The command can optionally send an acknowledgement once the local receptionist has removed the registration. The acknowledgement does not guarantee
that all subscribers has seen that the instance has been removed, it may still receive messages from subscribers for some time after this.

Scala
:  @@snip [ReceptionistExample](/akka-cluster-typed/src/test/scala/docs/akka/cluster/typed/ReceptionistExample.scala) { #deregister }

Java
:  @@snip [ReceptionistExample](/akka-cluster-typed/src/test/java/jdocs/akka/cluster/typed/ReceptionistExample.java) { #deregister }

## Cluster Receptionist

The `Receptionist` also works in a cluster, an actor registered to the receptionist will appear in the receptionist 
of the other nodes of the cluster.

The state for the receptionist is propagated via @ref:[distributed data](distributed-data.md) which means that each node
will eventually reach the same set of actors per `ServiceKey`.

`Subscription`s and `Find` queries to a clustered receptionist will keep track of cluster reachability and only list 
registered actors that are reachable. The full set of actors, including unreachable ones, is available through 
@scala[`Listing.allServiceInstances`]@java[`Listing.getAllServiceInstances`].

One important difference from local only receptions are the serialization concerns, all messages sent to and back from 
an actor on another node must be serializable, see @ref:[serialization](../serialization.md).

## Receptionist Scalability

The receptionist does not scale up to any number of services or very high turnaround of services. 
It will likely handle up to thousands or tens of thousands of services. Use cases with higher 
demands the receptionist for initial contact between actors on the nodes while the actual logic of those is up to the applications own actors. 
