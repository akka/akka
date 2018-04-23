# interleave

Emits a specifiable number of elements from the original source, then from the provided source and repeats.

@ref[Fan-in stages](../index.md#fan-in-stages)

@@@div { .group-scala }

## Signature

@@signature [Flow.scala]($akka$/akka-stream/src/main/scala/akka/stream/scaladsl/Flow.scala) { #interleave }

@@@

## Description

Emits a specifiable number of elements from the original source, then from the provided source and repeats. If one
source completes the rest of the other stream will be emitted.


@@@div { .callout }

**emits** when element is available from the currently consumed upstream

**backpressures** when upstream backpressures

**completes** when both upstreams have completed

@@@

