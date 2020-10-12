# backpressureTimeout

If the time between the emission of an element and the following downstream demand exceeds the provided timeout, the stream is failed with a `TimeoutException`.

@ref[Time aware operators](../index.md#time-aware-operators)

## Signature

@apidoc[Source.backpressureTimeout](Source) { scala="#backpressureTimeout(timeout:scala.concurrent.duration.FiniteDuration):FlowOps.this.Repr[Out]" java="#backpressureTimeout(java.time.Duration)" }
@apidoc[Flow.backpressureTimeout](Flow) { scala="#backpressureTimeout(timeout:scala.concurrent.duration.FiniteDuration):FlowOps.this.Repr[Out]" java="#backpressureTimeout(java.time.Duration)" }



## Description

If the time between the emission of an element and the following downstream demand exceeds the provided timeout,
the stream is failed with a `TimeoutException`. The timeout is checked periodically, so the resolution of the
check is one period (equals to timeout value).

## Reactive Streams semantics

@@@div { .callout }

**emits** when upstream emits an element

**backpressures** when downstream backpressures

**completes** when upstream completes or fails if timeout elapses between element emission and downstream demand.

**cancels** when downstream cancels

@@@

