# Flow.lazyInitAsync

`lazyInitAsync` has been deprecated in 2.6.0 use `Flow.lazyFutureFlow` in combination with `prefixAndTail` instead.

@ref[Simple operators](../index.md#simple-operators)

@@@div { .group-scala }

## Signature

@@signature [Flow.scala](/akka-stream/src/main/scala/akka/stream/scaladsl/Flow.scala) { #lazyInitAsync }

@@@

## Description

`fromCompletionStage` has been deprecated in 2.6.0 use @ref:[lazyFutureFlow](lazyFutureFlow.md) in combination with `prefixAndTail` instead.

Defers creation until a first element arrives.

## Reactive Streams semantics

@@@div { .callout }

**emits** when the internal flow is successfully created and it emits

**backpressures** when the internal flow is successfully created and it backpressures

**completes** when upstream completes and all elements have been emitted from the internal flow

**completes** when upstream completes and all futures have been completed and all elements have been emitted

@@@

