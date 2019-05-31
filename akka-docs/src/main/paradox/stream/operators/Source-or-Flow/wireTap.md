# wireTap

Attaches the given `Sink` to this `Flow` as a wire tap, meaning that elements that pass through will also be sent to the wire-tap `Sink`, without the latter affecting the mainline flow.

@ref[Fan-out operators](../index.md#fan-out-operators)

@@@div { .group-scala }

## Signature

@@signature [Flow.scala](/akka-stream/src/main/scala/akka/stream/scaladsl/Flow.scala) { #wireTap }

@@@

## Description

Attaches the given `Sink` to this `Flow` as a wire tap, meaning that elements that pass
through will also be sent to the wire-tap `Sink`, without the latter affecting the mainline flow.
If the wire-tap `Sink` backpressures, elements that would've been sent to it will be dropped instead.


@@@div { .callout }

**emits** element is available and demand exists from the downstream; the element will
also be sent to the wire-tap `Sink` if there is demand.

**backpressures** downstream backpressures

**completes** upstream completes

**cancels** downstream cancels

@@@

