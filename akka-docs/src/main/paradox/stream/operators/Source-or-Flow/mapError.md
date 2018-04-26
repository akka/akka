# mapError

While similar to `recover` this stage can be used to transform an error signal to a different one *without* logging it as an error in the process.

@ref[Simple processing stages](../index.md#simple-processing-stages)

@@@div { .group-scala }

## Signature

@@signature [Flow.scala]($akka$/akka-stream/src/main/scala/akka/stream/scaladsl/Flow.scala) { #mapError }

@@@

## Description

While similar to `recover` this stage can be used to transform an error signal to a different one *without* logging
it as an error in the process. So in that sense it is NOT exactly equivalent to `recover(t => throw t2)` since recover
would log the `t2` error.

Since the underlying failure signal onError arrives out-of-band, it might jump over existing elements.
This stage can recover the failure signal, but not the skipped elements, which will be dropped.

Similarily to `recover` throwing an exception inside `mapError` _will_ be logged on ERROR level automatically.


@@@div { .callout }

**emits** when element is available from the upstream or upstream is failed and pf returns an element
**backpressures** when downstream backpressures
**completes** when upstream completes or upstream failed with exception pf can handle

@@@

