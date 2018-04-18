# initialDelay

Delays the initial element by the specified duration.

@ref[Timer driven stages](../index.md#timer-driven-stages)

@@@div { .group-scala }

## Signature

@@signature [Flow.scala]($akka$/akka-stream/src/main/scala/akka/stream/scaladsl/Flow.scala) { #initialDelay }

@@@

## Description

Delays the initial element by the specified duration.


@@@div { .callout }

**emits** when upstream emits an element if the initial delay is already elapsed

**backpressures** when downstream backpressures or initial delay is not yet elapsed

**completes** when upstream completes

**cancels** when downstream cancels

@@@

