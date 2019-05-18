# mapAsync

Pass incoming elements to a function that return a @scala[`Future`] @java[`CompletionStage`] result.

@ref[Asynchronous operators](../index.md#asynchronous-operators)

@@@div { .group-scala }

## Signature

@@signature [Flow.scala](/akka-stream/src/main/scala/akka/stream/scaladsl/Flow.scala) { #mapAsync }

@@@

## Description

Pass incoming elements to a function that return a @scala[`Future`] @java[`CompletionStage`] result. When the @scala[`Future`] @java[`CompletionStage`] arrives the result is passed
downstream. Up to `n` elements can be processed concurrently, but regardless of their completion time the incoming
order will be kept when results complete. For use cases where order does not matter `mapAsyncUnordered` can be used.

If a @scala[`Future`] @java[`CompletionStage`] completes with `null`, element is not passed downstream.
If a @scala[`Future`] @java[`CompletionStage`] fails, the stream also fails (unless a different supervision strategy is applied)


@@@div { .callout }

**emits** when the @scala[`Future`] @java[`CompletionStage`] returned by the provided function finishes for the next element in sequence

**backpressures** when the number of @scala[`Future` s] @java[`CompletionStage` s] reaches the configured parallelism and the downstream backpressures

**completes** when upstream completes and all @scala[`Future` s] @java[`CompletionStage` s] has been completed and all elements has been emitted

@@@

