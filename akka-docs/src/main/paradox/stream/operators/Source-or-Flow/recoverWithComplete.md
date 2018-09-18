# recoverWithComplete

Allows to transform a stream failure into a successful stream.

@ref[Simple operators](../index.md#simple-operators)

@@@div { .group-scala }

## Signature

@@signature [Flow.scala](/akka-stream/src/main/scala/akka/stream/scaladsl/Flow.scala) { #recoverWithComplete }

@@@

## Description

RecoverWithComplete allows to transform a stream failure into a successful stream.

Since the underlying failure signal onError arrives out-of-band, it might jump over existing elements.
This stage can recover the failure signal, but not the skipped elements, which will be dropped.

Throwing an exception inside `recoverWithComplete` _will_ be logged on ERROR level automatically.

@@@div { .callout }

**emits** when element is available from the upstream

**backpressures** when downstream backpressures

**completes** when upstream completes or upstream failed

@@@
