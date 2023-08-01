# Distributed Publish Subscribe in Cluster

You are viewing the documentation for the new actor APIs, to view the Akka Classic documentation, see @ref:[Classic Distributed Publish Subscribe](../distributed-pub-sub.md).

## Module info

The distributed publish subscribe topic API is available and usable with the core `akka-actor-typed` module, however it will only be distributed
when used in a clustered application.

The Akka dependencies are available from Akka's library repository. To access them there, you need to configure the URL for this repository.

@@repository [sbt,Maven,Gradle] {
id="akka-repository"
name="Akka library repository"
url="https://repo.akka.io/maven"
}

Additionally, add the dependency as below.

@@dependency[sbt,Maven,Gradle] {
  bomGroup=com.typesafe.akka bomArtifact=akka-bom_$scala.binary.version$ bomVersionSymbols=AkkaVersion
  symbol1=AkkaVersion
  value1="$akka.version$"
  group="com.typesafe.akka"
  artifact="akka-cluster-typed_$scala.binary.version$"
  version=AkkaVersion
}

## The Topic Actor

Distributed publish subscribe is achieved by representing each pub sub topic with an actor, @apidoc[akka.actor.typed.pubsub.Topic](akka.actor.typed.pubsub.Topic$). 

The topic actor needs to run on each node where subscribers will live or that wants to publish messages to the topic.
 
The identity of the topic is a tuple of the type of messages that can be published and a string topic name but it is recommended
to not define multiple topics with different types and the same topic name.

Scala
:  @@snip [PubSubExample.scala](/akka-actor-typed-tests/src/test/scala/docs/akka/typed/pubsub/PubSubExample.scala) { #start-topic }

Java
:  @@snip [PubSubExample.java](/akka-actor-typed-tests/src/test/java/jdocs/akka/typed/pubsub/PubSubExample.java) { #start-topic }

Local actors can then subscribe to the topic (and unsubscribe from it):

Scala
:  @@snip [PubSubExample.scala](/akka-actor-typed-tests/src/test/scala/docs/akka/typed/pubsub/PubSubExample.scala) { #subscribe }

Java
:  @@snip [PubSubExample.java](/akka-actor-typed-tests/src/test/java/jdocs/akka/typed/pubsub/PubSubExample.java) { #subscribe }

And publish messages to the topic:

Scala
:  @@snip [PubSubExample.scala](/akka-actor-typed-tests/src/test/scala/docs/akka/typed/pubsub/PubSubExample.scala) { #publish }

Java
:  @@snip [PubSubExample.java](/akka-actor-typed-tests/src/test/java/jdocs/akka/typed/pubsub/PubSubExample.java) { #publish }

## Pub Sub Scalability

Each topic is represented by one @ref[Receptionist](actor-discovery.md) service key meaning that the number of topics 
will scale to thousands or tens of thousands but for higher numbers of topics will require custom solutions. It also means
that a very high turnaround of unique topics will not work well and for such use cases a custom solution is advised.

The topic actor acts as a proxy and delegates to the local subscribers handling deduplication so that a published message
is only sent once to a node regardless of how many subscribers there are to the topic on that node.

When a topic actor has no subscribers for a topic it will deregister itself from the receptionist meaning published messages
for the topic will not be sent to it.

## Delivery Guarantee

As in @ref:[Message Delivery Reliability](../general/message-delivery-reliability.md) of Akka, message delivery guarantee in distributed pub sub modes is **at-most-once delivery**. In other words, messages can be lost over the wire. In addition to that the registry of nodes which have subscribers is eventually consistent
meaning that subscribing an actor on one node will have a short delay before it is known on other nodes and published to.

If you are looking for at-least-once delivery guarantee, we recommend [Alpakka Kafka](https://doc.akka.io/docs/alpakka-kafka/current/).


