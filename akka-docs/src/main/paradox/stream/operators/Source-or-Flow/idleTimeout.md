# idleTimeout

If the time between two processed elements exceeds the provided timeout, the stream is failed with a `TimeoutException`.

@ref[Time aware operators](../index.md#time-aware-operators)

@@@div { .group-scala }

## Signature

@@signature [Flow.scala](/akka-stream/src/main/scala/akka/stream/scaladsl/Flow.scala) { #idleTimeout }

@@@

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

