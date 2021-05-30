# completionTimeout

If the completion of the stream does not happen until the provided timeout, the stream is failed with a `TimeoutException`.

@ref[Time aware operators](../index.md#time-aware-operators)

## Signature

@apidoc[Source.completionTimeout](Source) { scala="#completionTimeout(timeout:scala.concurrent.duration.FiniteDuration):FlowOps.this.Repr[Out]" java="#completionTimeout(java.time.Duration)" }
@apidoc[Flow.completionTimeout](Flow) { scala="#completionTimeout(timeout:scala.concurrent.duration.FiniteDuration):FlowOps.this.Repr[Out]" java="#completionTimeout(java.time.Duration)" }


## Description

If the completion of the stream does not happen until the provided timeout, the stream is failed
with a `TimeoutException`.

## Example

This example reads the numbers from a source and do some calculation in the flow with a completion timeout of 10 milliseconds. It will fail the stream, leading to failing the materialized @scala[`Future`] @java[`CompletionStage`] if the stream has not completed mapping the numbers from the source when the timeout hits.

Scala
:   @@snip [CompletionTimeout.scala](/akka-docs/src/test/scala/docs/stream/operators/sourceorflow/CompletionTimeout.scala) { #completionTimeout }

Java
:   @@snip [SourceOrFlow.java](/akka-docs/src/test/java/jdocs/stream/operators/SourceOrFlow.java) { #completionTimeout }


## Reactive Streams semantics

@@@div { .callout }

**emits** when upstream emits an element

**backpressures** when downstream backpressures

**completes** when upstream completes or fails if timeout elapses before upstream completes

**cancels** when downstream cancels

@@@

