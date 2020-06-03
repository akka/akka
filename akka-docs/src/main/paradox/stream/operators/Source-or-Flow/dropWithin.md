# dropWithin

Drop elements until a timeout has fired

@ref[Timer driven operators](../index.md#timer-driven-operators)

## Signature

@apidoc[Source.dropWithin](Source) { scala="#dropWithin(d:scala.concurrent.duration.FiniteDuration):FlowOps.this.Repr[Out]" java="#dropWithin(java.time.Duration)" }
@apidoc[Flow.dropWithin](Flow) { scala="#dropWithin(d:scala.concurrent.duration.FiniteDuration):FlowOps.this.Repr[Out]" java="#dropWithin(java.time.Duration)" }


## Description

Drop elements until a timeout has fired

## Reactive Streams semantics

@@@div { .callout }

**emits** after the timer fired and a new upstream element arrives

**backpressures** when downstream backpressures

**completes** upstream completes

@@@

