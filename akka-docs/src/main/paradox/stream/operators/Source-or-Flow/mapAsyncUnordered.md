# mapAsyncUnordered

Like `mapAsync` but @scala[`Future`] @java[`CompletionStage`] results are passed downstream as they arrive regardless of the order of the elements that triggered them.

@ref[Asynchronous operators](../index.md#asynchronous-operators)

@@@div { .group-scala }

## Signature

@@signature [Flow.scala](/akka-stream/src/main/scala/akka/stream/scaladsl/Flow.scala) { #mapAsyncUnordered }

@@@

## Description

Like `mapAsync` but @scala[`Future`] @java[`CompletionStage`] results are passed downstream as they arrive regardless of the order of the elements
that triggered them.

If a @scala[`Future`] @java[`CompletionStage`] completes with `null`, element is not passed downstream.
If a @scala[`Future`] @java[`CompletionStage`] fails, the stream also fails (unless a different supervision strategy is applied)

## Reactive Streams semantics

@@@div { .callout }

**emits** any of the @scala[`Future` s] @java[`CompletionStage` s] returned by the provided function complete

**backpressures** when the number of @scala[`Future` s] @java[`CompletionStage` s] reaches the configured parallelism and the downstream backpressures

**completes** upstream completes and all @scala[`Future` s] @java[`CompletionStage` s] has been completed  and all elements has been emitted

@@@

