# Sink.cancelled

Immediately cancel the stream

@ref[Sink operators](../index.md#sink-operators)

## Signature

@apidoc[Sink.cancelled](Sink$) { scala="#cancelled[T]:akka.stream.scaladsl.Sink[T,akka.NotUsed]" java="#cancelled()" }


## Description

Immediately cancel the stream

## Example

In this example, we have a source that generates numbers from 1 to 5 but as we have used cancelled we get `NotUsed` as materialized value and stream cancels.

Scala
:   @@snip [Cancelled.scala](/akka-docs/src/test/scala/docs/stream/operators/sink/Cancelled.scala) { #cancelled }

Java
:   @@snip [SinkDocExamples.java](/akka-docs/src/test/java/jdocs/stream/operators/SinkDocExamples.java) { #cancelled }

## Reactive Streams semantics

@@@div { .callout }

**cancels** immediately

@@@
