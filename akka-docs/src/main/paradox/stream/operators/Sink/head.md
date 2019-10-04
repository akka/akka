# head

Materializes into a @scala[`Future`] @java[`CompletionStage`] which completes with the first value arriving, after this the stream is canceled.

@ref[Sink operators](../index.md#sink-operators)

@@@div { .group-scala }

## Signature

@@signature [Sink.scala](/akka-stream/src/main/scala/akka/stream/scaladsl/Sink.scala) { #head }

@@@

## Description

Materializes into a @scala[`Future`] @java[`CompletionStage`] which completes with the first value arriving,
after this the stream is canceled. If no element is emitted, the @scala[`Future`] @java[`CompletionStage`] is failed.

## Reactive Streams semantics

@@@div { .callout }

**cancels** after receiving one element

**backpressures** never

@@@

