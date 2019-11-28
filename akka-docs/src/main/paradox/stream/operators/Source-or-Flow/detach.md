# detach

Detach upstream demand from downstream demand without detaching the stream rates.

@ref[Simple operators](../index.md#simple-operators)

@@@div { .group-scala }

## Signature

@@signature [Flow.scala](/akka-stream/src/main/scala/akka/stream/scaladsl/Flow.scala) { #detach }

@@@

## Description

Detach upstream demand from downstream demand without detaching the stream rates.

## Reactive Streams semantics

@@@div { .callout }

**emits** when the upstream operators has emitted and there is demand

**backpressures** when downstream backpressures

**completes** when upstream completes

@@@

