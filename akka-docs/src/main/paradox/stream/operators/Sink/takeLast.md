# Sink.takeLast

Materializes into a @scala[`Future`] @java[`CompletionStage`] of  @scala[`Seq[T]`] @java[`List<In>`] containing the last `n` collected elements when the stream completes.

@ref[Sink operators](../index.md#sink-operators)

@@@div { .group-scala }

## Signature

@@signature [Sink.scala]($akka$/akka-stream/src/main/scala/akka/stream/scaladsl/Sink.scala) { #takeLast }

@@@

## Description

Materializes into a @scala[`Future`] @java[`CompletionStage`] which will complete with the last value emitted when the stream
completes. If the stream completes with no elements the @scala[`Future`] @java[`CompletionStage`] is failed.


@@@div { .callout }

**cancels** never

**backpressures** never

@@@


