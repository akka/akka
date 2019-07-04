# Streams

## Dependency

To use Akka Streams Typed, add the module to your project:

@@dependency [sbt,Maven,Gradle] {
  group=com.typesafe.akka
  artifact=akka-stream-typed_$scala.binary_version$
  version=$akka.version$
}

## Introduction

@ref:[Akka Streams](../stream/index.md) make it easy to model type-safe message processing pipelines. With typed actors it is possible to connect streams to actors without losing the type information.

This module contains typed alternatives to the @ref:[already existing `ActorRef` sources and sinks](../stream/stream-integrations.md) together with a factory methods for
@scala[@scaladoc[`ActorMaterializer`](akka.stream.typed.scaladsl.ActorMaterializer)]@java[@javadoc[`ActorMaterializerFactory`](akka.stream.typed.javadsl.ActorMaterializerFactory)] which takes a typed `ActorSystem`.

The materializer created from these factory methods and sources together with sinks contained in this module can be mixed and matched with the original Akka Streams building blocks from the original module.

## Actor Source

A stream that is driven by messages sent to a particular actor can be started with @scala[@scaladoc[`ActorSource.actorRef`](akka.stream.typed.scaladsl.ActorSource#actorRef)]@java[@javadoc[`ActorSource.actorRef`](akka.stream.typed.javadsl.ActorSource#actorRef)]. This source materializes to a typed `ActorRef` which only accepts messages that are of the same type as the stream.

Scala
:  @@snip [ActorSourceSinkExample.scala](/akka-stream-typed/src/test/scala/docs/akka/stream/typed/ActorSourceSinkExample.scala) { #actor-source-ref }

Java
:  @@snip [ActorSourceExample.java](/akka-stream-typed/src/test/java/docs/akka/stream/typed/ActorSourceExample.java) { #actor-source-ref }


## Actor Sink

There are two sinks available that accept typed `ActorRef`s. To send all of the messages from a stream to an actor without considering backpressure, use @scala[@scaladoc[`ActorSink.actorRef`](akka.stream.typed.scaladsl.ActorSink#actorRef)]@java[@javadoc[`ActorSink.actorRef`](akka.stream.typed.javadsl.ActorSink#actorRef)].

Scala
:  @@snip [ActorSourceSinkExample.scala](/akka-stream-typed/src/test/scala/docs/akka/stream/typed/ActorSourceSinkExample.scala) { #actor-sink-ref }

Java
:  @@snip [ActorSinkExample.java](/akka-stream-typed/src/test/java/docs/akka/stream/typed/ActorSinkExample.java) { #actor-sink-ref }

For an actor to be able to react to backpressure, a protocol needs to be introduced between the actor and the stream. Use @scala[@scaladoc[`ActorSink.actorRefWithAck`](akka.stream.typed.scaladsl.ActorSink#actorRefWithAck)]@java[@javadoc[`ActorSink.actorRefWithAck`](akka.stream.typed.javadsl.ActorSink#actorRefWithAck)] to be able to signal demand when the actor is ready to receive more elements.

Scala
:  @@snip [ActorSourceSinkExample.scala](/akka-stream-typed/src/test/scala/docs/akka/stream/typed/ActorSourceSinkExample.scala) { #actor-sink-ref-with-ack }

Java
:  @@snip [ActorSinkWithAckExample.java](/akka-stream-typed/src/test/java/docs/akka/stream/typed/ActorSinkWithAckExample.java) { #actor-sink-ref-with-ack }
