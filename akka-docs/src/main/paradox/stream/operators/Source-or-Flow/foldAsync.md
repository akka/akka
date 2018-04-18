# foldAsync

Just like `fold` but receiving a function that results in a @scala[`Future`] @java[`CompletionStage`] to the next value.

@ref[Simple processing stages](../index.md#simple-processing-stages)

@@@div { .group-scala }

## Signature

@@signature [Flow.scala]($akka$/akka-stream/src/main/scala/akka/stream/scaladsl/Flow.scala) { #foldAsync }

@@@

## Description

Just like `fold` but receiving a function that results in a @scala[`Future`] @java[`CompletionStage`] to the next value.


@@@div { .callout }

**emits** when upstream completes and the last @scala[`Future`] @java[`CompletionStage`] is resolved

**backpressures** when downstream backpressures

**completes** when upstream completes and the last @scala[`Future`] @java[`CompletionStage`] is resolved

@@@

