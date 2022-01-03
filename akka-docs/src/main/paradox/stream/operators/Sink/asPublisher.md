# Sink.asPublisher

Integration with Reactive Streams, materializes into a `org.reactivestreams.Publisher`.

@ref[Sink operators](../index.md#sink-operators)

## Signature

@apidoc[Sink.asPublisher](Sink$) { scala="#asPublisher[T](fanout:Boolean):akka.stream.scaladsl.Sink[T,org.reactivestreams.Publisher[T]]" java="#asPublisher(akka.stream.javadsl.AsPublisher)" }



## Description

This method gives you the capability to publish the data from the `Sink` through a Reactive Streams [Publisher](https://www.reactive-streams.org/reactive-streams-1.0.3-javadoc/org/reactivestreams/Publisher.html).
Generally, in Akka Streams a `Sink` is considered a subscriber, which consumes the data from source. To integrate with other Reactive Stream implementations `Sink.asPublisher` provides a `Publisher` materialized value when run.
Now, the data from this publisher can be consumed by subscribing to it. We can control if we allow more than one downstream subscriber from the single running Akka stream through the `fanout` parameter.
In Java 9, the Reactive Stream API was included in the JDK, and `Publisher` is available through [Flow.Publisher](https://docs.oracle.com/javase/9/docs/api/java/util/concurrent/Flow.Publisher.html).
Since those APIs are identical but exist at different package namespaces and does not depend on the Reactive Streams package a separate publisher sink for those is available 
through @scala[`akka.stream.scaladsl.JavaFlowSupport.Sink#asPublisher`]@java[`akka.stream.javadsl.JavaFlowSupport.Sink#asPublisher`].


## Example

In the example we are using a source and then creating a Publisher. After that, we see that when `fanout` is true multiple subscribers can subscribe to it, 
but when it is false only the first subscriber will be able to subscribe and others will be rejected.

Scala
:   @@snip [AsPublisher.scala](/akka-docs/src/test/scala/docs/stream/operators/sink/AsPublisher.scala) { #asPublisher }

Java
:   @@snip [SinkDocExamples.java](/akka-docs/src/test/java/jdocs/stream/operators/SinkDocExamples.java) { #asPublisher }

## Reactive Streams semantics

@@@div { .callout }

**emits** the materialized publisher

**completes** after the source is consumed and materialized publisher is created

@@@
