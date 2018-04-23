# dropWithin

Drop elements until a timeout has fired

@ref[Timer driven stages](../index.md#timer-driven-stages)

@@@div { .group-scala }

## Signature

@@signature [Flow.scala]($akka$/akka-stream/src/main/scala/akka/stream/scaladsl/Flow.scala) { #dropWithin }

@@@

## Description

Drop elements until a timeout has fired


@@@div { .callout }

**emits** after the timer fired and a new upstream element arrives

**backpressures** when downstream backpressures

**completes** upstream completes

@@@

