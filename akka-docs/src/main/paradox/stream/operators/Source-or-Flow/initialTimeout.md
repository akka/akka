# initialTimeout

If the first element has not passed through this operators before the provided timeout, the stream is failed with a `TimeoutException`.

@ref[Time aware operators](../index.md#time-aware-operators)

## Signature

@apidoc[Source.initialTimeout](Source) { scala="#initialTimeout(timeout:scala.concurrent.duration.FiniteDuration):FlowOps.this.Repr[Out]" java="#initialTimeout(java.time.Duration)" }
@apidoc[Flow.initialTimeout](Flow) { scala="#initialTimeout(timeout:scala.concurrent.duration.FiniteDuration):FlowOps.this.Repr[Out]" java="#initialTimeout(java.time.Duration)" }


## Description

If the first element has not passed through this operators before the provided timeout, the stream is failed
with a `TimeoutException`.

## Reactive Streams semantics

@@@div { .callout }

**emits** when upstream emits an element

**backpressures** when downstream backpressures

**completes** when upstream completes or fails if timeout elapses before first element arrives

**cancels** when downstream cancels

@@@

