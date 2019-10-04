# scanAsync

Just like `scan` but receives a function that results in a @scala[`Future`] @java[`CompletionStage`] to the next value.

@ref[Simple operators](../index.md#simple-operators)

@@@div { .group-scala }

## Signature

@@signature [Flow.scala](/akka-stream/src/main/scala/akka/stream/scaladsl/Flow.scala) { #scanAsync }

@@@

## Description

Just like `scan` but receives a function that results in a @scala[`Future`] @java[`CompletionStage`] to the next value.

Note that the `zero` value must be immutable.

## Reactive Streams semantics

@@@div { .callout }

**emits** when the @scala[`Future`] @java[`CompletionStage`] resulting from the function scanning the element resolves to the next value

**backpressures** when downstream backpressures

**completes** when upstream completes and the last @scala[`Future`] @java[`CompletionStage`] is resolved

@@@

