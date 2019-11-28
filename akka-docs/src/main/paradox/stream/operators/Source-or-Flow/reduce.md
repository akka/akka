# reduce

Start with first element and then apply the current and next value to the given function, when upstream complete the current value is emitted downstream.

@ref[Simple operators](../index.md#simple-operators)

@@@div { .group-scala }

## Signature

@@signature [Flow.scala](/akka-stream/src/main/scala/akka/stream/scaladsl/Flow.scala) { #reduce }

@@@

## Description

Start with first element and then apply the current and next value to the given function, when upstream
complete the current value is emitted downstream. Similar to `fold`.

## Reactive Streams semantics

@@@div { .callout }

**emits** when upstream completes

**backpressures** when downstream backpressures

**completes** when upstream completes

@@@

