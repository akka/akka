# Sink.lazyInitAsync

Creates a real `Sink` upon receiving the first element. 

@ref[Sink operators](../index.md#sink-operators)

@@@div { .group-scala }

## Signature

@@signature [Sink.scala](/akka-stream/src/main/scala/akka/stream/scaladsl/Sink.scala) { #lazyInitAsync }

@@@

## Description

Creates a real `Sink` upon receiving the first element. Internal `Sink` will not be created if there are no elements,
because of completion or error.

- If upstream completes before an element was received then the @scala[`Future`]@java[`CompletionStage`] is completed with @scala[`None`]@java[an empty `Optional`].
- If upstream fails before an element was received, `sinkFactory` throws an exception, or materialization of the internal
  sink fails then the @scala[`Future`]@java[`CompletionStage`] is completed with the exception.
- Otherwise the @scala[`Future`]@java[`CompletionStage`] is completed with the materialized value of the internal sink.

@@@div { .callout }

**cancels** never

**backpressures** when initialized and when created sink backpressures

@@@


