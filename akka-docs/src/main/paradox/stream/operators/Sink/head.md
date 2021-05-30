# Sink.head

Materializes into a @scala[`Future`] @java[`CompletionStage`] which completes with the first value arriving, after this the stream is canceled.

@ref[Sink operators](../index.md#sink-operators)

## Signature

@apidoc[Sink.head](Sink$) { scala="#head[T]:akka.stream.scaladsl.Sink[T,scala.concurrent.Future[T]]" java="#head()" }


## Description

Materializes into a @scala[`Future`] @java[`CompletionStage`] which completes with the first value arriving,
after this the stream is canceled. If no element is emitted, the @scala[`Future`] @java[`CompletionStage`] is failed.

## Example

Scala
:   @@snip [HeadSinkSpec.scala](/akka-stream-tests/src/test/scala/akka/stream/scaladsl/HeadSinkSpec.scala) { #head-operator-example }

Java
:   @@snip [SinkDocExamples.java](/akka-docs/src/test/java/jdocs/stream/operators/SinkDocExamples.java) { #head-operator-example }

## Reactive Streams semantics

@@@div { .callout }

**cancels** after receiving one element

**backpressures** never

@@@

