# Sink.last

Materializes into a @scala[`Future`] @java[`CompletionStage`] which will complete with the last value emitted when the stream completes.

@ref[Sink operators](../index.md#sink-operators)

@@@div { .group-scala }

## Signature

@@signature [Sink.scala](/akka-stream/src/main/scala/akka/stream/scaladsl/Sink.scala) { #last }

@@@

## Description

Materializes into a @scala[`Future`] @java[`CompletionStage`] which will complete with the last value emitted when the stream
completes. If the stream completes with no elements the @scala[`Future`] @java[`CompletionStage`] is failed.


@@@div { .callout }

**cancels** never

**backpressures** never

@@@


