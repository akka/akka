# completionTimeout

If the completion of the stream does not happen until the provided timeout, the stream is failed with a `TimeoutException`.

@ref[Time aware operators](../index.md#time-aware-operators)

## Signature

@apidoc[Source.completionTimeout](Source) { scala="#completionTimeout(timeout:scala.concurrent.duration.FiniteDuration):FlowOps.this.Repr[Out]" java="#completionTimeout(java.time.Duration)" }
@apidoc[Flow.completionTimeout](Flow) { scala="#completionTimeout(timeout:scala.concurrent.duration.FiniteDuration):FlowOps.this.Repr[Out]" java="#completionTimeout(java.time.Duration)" }


## Description

If the completion of the stream does not happen until the provided timeout, the stream is failed
with a `TimeoutException`.

## Reactive Streams semantics

@@@div { .callout }

**emits** when upstream emits an element

**backpressures** when downstream backpressures

**completes** when upstream completes or fails if timeout elapses before upstream completes

**cancels** when downstream cancels

@@@

