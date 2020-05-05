# idleTimeout

If the time between two processed elements exceeds the provided timeout, the stream is failed with a `TimeoutException`.

@ref[Time aware operators](../index.md#time-aware-operators)

## Signature

@apidoc[Source.idleTimeout](Source) { scala="#idleTimeout(timeout:scala.concurrent.duration.FiniteDuration):FlowOps.this.Repr[Out]" java="#idleTimeout(java.time.Duration)" }
@apidoc[Flow.idleTimeout](Flow) { scala="#idleTimeout(timeout:scala.concurrent.duration.FiniteDuration):FlowOps.this.Repr[Out]" java="#idleTimeout(java.time.Duration)" }

## Description

If the time between two processed elements exceeds the provided timeout, the stream is failed
with a `TimeoutException`. The timeout is checked periodically, so the resolution of the
check is one period (equals to timeout value).

## Reactive Streams semantics

@@@div { .callout }

**emits** when upstream emits an element

**backpressures** when downstream backpressures

**completes** when upstream completes or fails if timeout elapses between two emitted elements

**cancels** when downstream cancels

@@@

