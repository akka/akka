# recoverWithRetries

RecoverWithRetries allows to switch to alternative Source on flow failure.

@ref[Simple operators](../index.md#simple-operators)

@@@div { .group-scala }

## Signature

@@signature [Flow.scala](/akka-stream/src/main/scala/akka/stream/scaladsl/Flow.scala) { #recoverWithRetries }

@@@

## Description

RecoverWithRetries allows to switch to alternative Source on flow failure. It will stay in effect after
a failure has been recovered up to *attempts* number of times so that each time there is a failure
it is fed into the *pf* and a new Source may be materialized. Note that if you pass in 0, this won't
attempt to recover at all. A negative `attempts` number is interpreted as "infinite", which results in the exact same behavior as `recoverWith`.

Since the underlying failure signal onError arrives out-of-band, it might jump over existing elements.
This operators can recover the failure signal, but not the skipped elements, which will be dropped.

## Reactive Streams semantics

@@@div { .callout }

**emits** when element is available from the upstream or upstream is failed and element is available from alternative Source

**backpressures** when downstream backpressures

**completes** when upstream completes or upstream failed with exception pf can handle

@@@

