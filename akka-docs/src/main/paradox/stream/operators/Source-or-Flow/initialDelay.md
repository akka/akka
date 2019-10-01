# initialDelay

Delays the initial element by the specified duration.

@ref[Timer driven operators](../index.md#timer-driven-operators)

@@@div { .group-scala }

## Signature

@@signature [Flow.scala](/akka-stream/src/main/scala/akka/stream/scaladsl/Flow.scala) { #initialDelay }

@@@

## Description

Delays the initial element by the specified duration.

## Reactive Streams semantics

@@@div { .callout }

**emits** when upstream emits an element if the initial delay is already elapsed

**backpressures** when downstream backpressures or initial delay is not yet elapsed

**completes** when upstream completes

**cancels** when downstream cancels

@@@

