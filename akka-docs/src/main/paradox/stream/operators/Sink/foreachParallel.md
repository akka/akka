# foreachParallel

Like `foreach` but allows up to `parallellism` procedure calls to happen in parallel.

@ref[Sink stages](../index.md#sink-stages)

@@@div { .group-scala }

## Signature

@@signature [Sink.scala]($akka$/akka-stream/src/main/scala/akka/stream/scaladsl/Sink.scala) { #foreachParallel }

@@@

## Description

Like `foreach` but allows up to `parallellism` procedure calls to happen in parallel.


@@@div { .callout }

**cancels** never

**backpressures** when the previous parallel procedure invocations has not yet completed

@@@

