# Sink.last

Materializes into a @scala[`Future`] @java[`CompletionStage`] which will complete with the last value emitted when the stream completes.

@ref[Sink operators](../index.md#sink-operators)

## Signature

@apidoc[Sink.last](Sink$) { scala="#last[T]:akka.stream.scaladsl.Sink[T,scala.concurrent.Future[T]]" java="#last()" }


## Description

Materializes into a @scala[`Future`] @java[`CompletionStage`] which will complete with the last value emitted when the stream
completes. If the stream completes with no elements the @scala[`Future`] @java[`CompletionStage`] is failed.

## Example

Scala
:   @@snip [LastSinkSpec.scala](/akka-stream-tests/src/test/scala/akka/stream/scaladsl/LastSinkSpec.scala) { #last-operator-example }

Java
:   @@snip [SinkDocExamples.java](/akka-docs/src/test/java/jdocs/stream/operators/SinkDocExamples.java) { #last-operator-example }

## Reactive Streams semantics

@@@div { .callout }

**cancels** never

**backpressures** never

@@@
