# initialDelay

Delays the initial element by the specified duration.

@ref[Timer driven operators](../index.md#timer-driven-operators)

## Signature

@apidoc[Source.initialDelay](Source$) { scala="#initialDelay(delay:scala.concurrent.duration.FiniteDuration):FlowOps.this.Repr[Out]" java="#initialDelay(java.time.Duration)" }
@apidoc[Flow.initialDelay](Flow) { scala="#initialDelay(delay:scala.concurrent.duration.FiniteDuration):FlowOps.this.Repr[Out]" java="#initialDelay(java.time.Duration)" }


## Description

Delays the initial element by the specified duration.

## Reactive Streams semantics

@@@div { .callout }

**emits** when upstream emits an element if the initial delay is already elapsed

**backpressures** when downstream backpressures or initial delay is not yet elapsed

**completes** when upstream completes

**cancels** when downstream cancels

@@@

