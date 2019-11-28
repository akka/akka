# watchTermination

Materializes to a @scala[`Future`] @java[`CompletionStage`] that will be completed with Done or failed depending whether the upstream of the operators has been completed or failed.

@ref[Watching status operators](../index.md#watching-status-operators)

@@@div { .group-scala }

## Signature

@@signature [Flow.scala](/akka-stream/src/main/scala/akka/stream/scaladsl/Flow.scala) { #watchTermination }

@@@

## Description

Materializes to a @scala[`Future`] @java[`CompletionStage`] that will be completed with Done or failed depending whether the upstream of the operators has been completed or failed.
The operators otherwise passes through elements unchanged.

## Reactive Streams semantics

@@@div { .callout }

**emits** when input has an element available

**backpressures** when output backpressures

**completes** when upstream completes

@@@

