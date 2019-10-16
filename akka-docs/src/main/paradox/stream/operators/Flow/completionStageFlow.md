# Flow.completionStageFlow

Streams the elements through the given future flow once it successfully completes.

@ref[Simple operators](../index.md#simple-operators)

@@@div { .group-scala }

## Signature

@@signature [Flow.scala](/akka-stream/src/main/scala/akka/stream/scaladsl/Flow.scala) { #futureFlow }

@@@

## Description

Streams the elements through the given flow once the `CompletionStage` successfully completes. 
If the future fails the stream is failed.

## Examples

A deferred creation of the stream based on the initial element by combining `completionStageFlow`
with `prefixAndTail` like so:

Scala
:   @@snip [FutureFlow.java](/akka-docs/src/test/java/jdocs/stream/operators/flow/FutureFlow.java) { #base-on-first-element }


## Reactive Streams semantics

@@@div { .callout }

**emits** when the internal flow is successfully created and it emits

**backpressures** when the internal flow is successfully created and it backpressures

**completes** when upstream completes and all elements have been emitted from the internal flow

**completes** when upstream completes and all futures have been completed and all elements have been emitted

@@@

