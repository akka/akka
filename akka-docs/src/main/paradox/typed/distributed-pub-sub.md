# Distributed Publish Subscribe in Cluster

For the Akka Classic documentation of this feature see [Classic Distributed Publish Subscribe](../distributed-pub-sub.md).
Classic Pub Sub can be used by leveraging the `.toClassic` adapters until @github[#26338](#26338).

## Module info

Until the new Distributed Publish Subscribe API, see @github[#26338](#26338), 
you can use Classic Distributed Publish Subscribe 
[coexisting](coexisting.md) with new Cluster and actors. To do this, add following dependency in your project:

@@dependency[sbt,Maven,Gradle] {
  group="com.typesafe.akka"
  artifact="akka-cluster-tools_$scala.binary_version$"
  version="$akka.version$"
}

Add the new Cluster API if you don't already have it in an existing Cluster application:

@@dependency[sbt,Maven,Gradle] {
  group=com.typesafe.akka
  artifact=akka-cluster-typed_$scala.binary_version$
  version=$akka.version$
}

@@project-info{ projectId="akka-cluster-typed" }

## Sample project

Until @github[#26338](#26338), [this simple example]($github.base_url$/akka-cluster-typed/src/test/scala/docs/akka/cluster/typed/DistributedPubSubExample.scala) shows how to use 
@ref:[Classic Distributed Publish Subscribe](../distributed-pub-sub.md) with the new Cluster API.

### The DistributedPubSub extension

The mediator can either be started and accessed with the `akka.cluster.pubsub.DistributedPubSub` extension as shown below,
or started as an ordinary actor, see the full Akka Classic documentation @ref:[Clasic Distributed PubSub Extension](../distributed-pub-sub.md#distributedpubsub-extension).

Scala
:  @@snip [DistributedPubSubExample.scala](/akka-cluster-typed/src/test/scala/docs/akka/cluster/typed/DistributedPubSubExample.scala) { #mediator }
 
Actors register to a topic for Pub-Sub mode, or register to a path for point-to-point mode. 

## Publish

Pub-Sub mode. For the full Akka Classic documentation of this feature see @ref:[Clasic Distributed PubSub Publish](../distributed-pub-sub.md#publish).

### Subscribers

Subscriber actors can be started on several nodes in the cluster, and all will receive
messages published to the "content" topic. 

An actor that subscribes to a topic:

Scala
:  @@snip [DistributedPubSubExample.scala](/akka-cluster-typed/src/test/scala/docs/akka/cluster/typed/DistributedPubSubExample.scala) { #subscriber }


Actors may also be subscribed to a named topic with a `group` id. 
For the full feature description see @ref:[topic groups](../distributed-pub-sub.md#topic-groups).

### Publishers

Publishers publish messages to the topic from anywhere in the cluster.
Messages are published by sending `DistributedPubSubMediator.Publish` message to the
local mediator.

An actor that publishes to a topic:

Scala
:  @@snip [DistributedPubSubExample.scala](/akka-cluster-typed/src/test/scala/docs/akka/cluster/typed/DistributedPubSubExample.scala) { #publisher }

## Send

Messages can be sent in point-to-point or broadcast mode. For the full Akka Classic documentation of this feature see @ref:[Clasic Distributed PubSub Send](../distributed-pub-sub.md#send). 

First, an actor must register a destination to send to:

Scala
:  @@snip [DistributedPubSubExample.scala](/akka-cluster-typed/src/test/scala/docs/akka/cluster/typed/DistributedPubSubExample.scala) { #destination }

An actor that sends to a registered path:

Scala
:  @@snip [DistributedPubSubExample.scala](/akka-cluster-typed/src/test/scala/docs/akka/cluster/typed/DistributedPubSubExample.scala) { #send }

Actors are automatically removed from the registry when they are terminated, or you
can explicitly remove entries with `DistributedPubSubMediator.Remove`.
 
## Delivery Guarantee

For the full Akka Classic documentation of this see @ref:[Clasic Distributed PubSub Delivery Guarantee](../distributed-pub-sub.md#delivery-guarantee).
