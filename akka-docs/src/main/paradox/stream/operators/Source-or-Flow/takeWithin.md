# takeWithin

Pass elements downstream within a timeout and then complete.

@ref[Timer driven operators](../index.md#timer-driven-operators)

@@@div { .group-scala }

## Signature

@@signature [Flow.scala](/akka-stream/src/main/scala/akka/stream/scaladsl/Flow.scala) { #takeWithin }

@@@

## Description

Pass elements downstream within a timeout and then complete.

## Reactive Streams semantics

@@@div { .callout }

**emits** when an upstream element arrives

**backpressures** downstream backpressures

**completes** upstream completes or timer fires

@@@

