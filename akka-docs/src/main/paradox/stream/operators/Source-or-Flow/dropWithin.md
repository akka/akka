# dropWithin

Drop elements until a timeout has fired

@ref[Timer driven operators](../index.md#timer-driven-operators)

@@@div { .group-scala }

## Signature

@@signature [Flow.scala](/akka-stream/src/main/scala/akka/stream/scaladsl/Flow.scala) { #dropWithin }

@@@

## Description

Drop elements until a timeout has fired

## Reactive Streams semantics

@@@div { .callout }

**emits** after the timer fired and a new upstream element arrives

**backpressures** when downstream backpressures

**completes** upstream completes

@@@

