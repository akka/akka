# Sink.asPublisher

Integration with Reactive Streams, materializes into a `org.reactivestreams.Publisher`.

@ref[Sink operators](../index.md#sink-operators)

## Signature

@apidoc[Sink.asPublisher](Sink$) { scala="#asPublisher[T](fanout:Boolean):akka.stream.scaladsl.Sink[T,org.reactivestreams.Publisher[T]]" java="#asPublisher(akka.stream.javadsl.AsPublisher)" }



## Description

This method gives you the capability to publish the data from the Sink. Generally, in akka streams Sink is considered as subscriber,
which consumes the data from source. But, what if you want to reuse the data from Sink? `Sink.asPublisher` provides you a materialized Publisher.
Now, the data from this publisher can be consumed by subscribing to it. We can control if we want a single subscriber or multiple subscribers for this publisher by setting `fanout`.
If `fanout` is true then Publisher will support Multiple Subscribers otherwise it will support only Single Subscriber. 


## Example

In the example I'm using a source and then creating a Publisher. After that, we see that when `fanout` is true multiple subscribers can subscribe to it, 
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

