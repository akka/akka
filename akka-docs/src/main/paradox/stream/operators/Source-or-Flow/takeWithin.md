# takeWithin

Pass elements downstream within a timeout and then complete.

@ref[Timer driven stages](../index.md#timer-driven-stages)

@@@div { .group-scala }

## Signature

@@signature [Flow.scala]($akka$/akka-stream/src/main/scala/akka/stream/scaladsl/Flow.scala) { #takeWithin }

@@@

## Description

Pass elements downstream within a timeout and then complete.


@@@div { .callout }

**emits** when an upstream element arrives

**backpressures** downstream backpressures

**completes** upstream completes or timer fires

@@@

