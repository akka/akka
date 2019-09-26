# delayWith

Delay every element passed through with a duration that can be controlled dynamically.

@ref[Timer driven operators](../index.md#timer-driven-operators)

@@@div { .group-scala }

## Signature

@@signature [Flow.scala](/akka-stream/src/main/scala/akka/stream/scaladsl/Flow.scala) { #delayWith }

@@@

## Description

Delay every element passed through with a duration that can be controlled dynamically, individually for each elements (via the `DelayStrategy`).


@@@div { .callout }

**emits** there is a pending element in the buffer and configured time for this element elapsed

**backpressures** differs, depends on `OverflowStrategy` set

**completes** when upstream completes and buffered elements has been drained


@@@

