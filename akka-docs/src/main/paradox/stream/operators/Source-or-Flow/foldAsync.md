# foldAsync

Just like `fold` but receives a function that results in a @scala[`Future`] @java[`CompletionStage`] to the next value.

@ref[Simple operators](../index.md#simple-operators)

@@@div { .group-scala }

## Signature

@@signature [Flow.scala](/akka-stream/src/main/scala/akka/stream/scaladsl/Flow.scala) { #foldAsync }

@@@

## Description

Just like `fold` but receives a function that results in a @scala[`Future`] @java[`CompletionStage`] to the next value.

Note that the `zero` value must be immutable.

## Reactive Streams semantics

@@@div { .callout }

**emits** when upstream completes and the last @scala[`Future`] @java[`CompletionStage`] is resolved

**backpressures** when downstream backpressures

**completes** when upstream completes and the last @scala[`Future`] @java[`CompletionStage`] is resolved

@@@

