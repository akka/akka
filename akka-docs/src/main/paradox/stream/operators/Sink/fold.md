# fold

Fold over emitted element with a function, where each invocation will get the new element and the result from the previous fold invocation.

@ref[Sink stages](../index.md#sink-stages)

@@@div { .group-scala }

## Signature

@@signature [Sink.scala]($akka$/akka-stream/src/main/scala/akka/stream/scaladsl/Sink.scala) { #fold }

@@@

## Description

Fold over emitted element with a function, where each invocation will get the new element and the result from the
previous fold invocation. The first invocation will be provided the `zero` value.

Materializes into a @scala[`Future`] @java[`CompletionStage`] that will complete with the last state when the stream has completed.

This stage allows combining values into a result without a global mutable state by instead passing the state along
between invocations.

@@@div { .callout }

**cancels** never

**backpressures** when the previous fold function invocation has not yet completed

@@@div

