# Actor discovery

## Dependency

To use Akka Actor Typed, you must add the following dependency in your project:

@@dependency[sbt,Maven,Gradle] {
  group=com.typesafe.akka
  artifact=akka-actor-typed_$scala.binary_version$
  version=$akka.version$
}

## Introduction

With @ref:[untyped actors](../general/addressing.md) you would use `ActorSelection` to "lookup" actors. Given an actor path with
address information you can get hold of an `ActorRef` to any actor. `ActorSelection` does not exist in Akka Typed, 
so how do you get the actor references? You can send refs in messages but you need something to bootstrap the interaction.

## Receptionist

For this purpose there is an actor called the `Receptionist`. You register the specific actors that should be discoverable 
from other nodes in the local `Receptionist` instance. The API of the receptionist is also based on actor messages. 
This registry of actor references is then automatically distributed to all other nodes in the cluster. 
You can lookup such actors with the key that was used when they were registered. The reply to such a `Find` request is 
a `Listing`, which contains a `Set` of actor references that are registered for the key. Note that several actors can be 
registered to the same key.

The registry is dynamic. New actors can be registered during the lifecycle of the system. Entries are removed when 
registered actors are stopped or a node is removed from the cluster. To facilitate this dynamic aspect you can also subscribe 
to changes with the `Receptionist.Subscribe` message. It will send `Listing` messages to the subscriber when entries for a key are changed.

The primary scenario for using the receptionist is when an actor needs to be discovered by another actor but you are unable
to put a reference to it in an incoming message.

These imports are used in the following example:

Scala
:  @@snip [ReceptionistExample](/akka-cluster-typed/src/test/scala/docs/akka/cluster/typed/ReceptionistExample.scala) { #import }

Java
:  @@snip [ReceptionistExample](/akka-cluster-typed/src/test/java/jdocs/akka/cluster/typed/ReceptionistExample.java) { #import }

First we create a @scala[`pingService`]@java[`PingService`] actor and register it with the `Receptionist` against a
`ServiceKey` that will later be used to lookup the reference:

Scala
:  @@snip [ReceptionistExample](/akka-cluster-typed/src/test/scala/docs/akka/cluster/typed/ReceptionistExample.scala) { #ping-service }

Java
:  @@snip [ReceptionistExample](/akka-cluster-typed/src/test/java/jdocs/akka/cluster/typed/ReceptionistExample.java) { #ping-service }

Then we have another actor that requires a @scala[`pingService`]@java[`PingService`] to be constructed:

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

Each time a new (which is just a single time in this example) @scala[`pingService`]@java[`PingService`] is registered the
guardian actor spawns a @scala[`pinger`]@java[`Pinger`] for each currently known `PingService`. The @scala[`pinger`]@java[`Pinger`]
sends a `Ping` message and when receiving the `Pong` reply it stops.

## Cluster Receptionist

The `Receptionist` also works in a cluster, an actor registered to the receptionist will appear in the receptionist of the other nodes of the cluster.

The state for the receptionist is propagated via @ref:[distributed data](../distributed-data.md) which means that each node will eventually reach the same set of actors per `ServiceKey`.

One important difference from a local only receptions is the serialisation concerns, all messages sent to and back from an actor on another node must be serializable, see @ref:[clustering](cluster.md#serialization).
