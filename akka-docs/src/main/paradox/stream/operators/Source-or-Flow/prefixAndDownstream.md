# prefixAndDownstream

Take up to *n* elements from the stream (less than *n* only if the upstream completes before emitting *n* elements), then apply *f* on these elements in order to obtain a flow, this flow is then materialized and the rest of the input is processed by this flow (similar to via). This method returns a flow consuming the rest of the stream producing the materialized flow's output.

@ref[Nesting and flattening operators](../index.md#nesting-and-flattening-operators)

@@@div { .group-scala }

## Signature

@@signature [Flow.scala](/akka-stream/src/main/scala/akka/stream/scaladsl/Flow.scala) { #prefixAndDownstream }

@@@

## Description

Take up to *n* elements from the stream (less than *n* only if the upstream completes before emitting *n* elements),
then apply *f* on these elements in order to obtain a flow, this flow is then materialized and the rest of the input is processed by this flow (similar to via).
This method returns a flow consuming the rest of the stream producing the materialized flow's output.

## Reactive Streams semantics

@@@div { .callout }

**emits** the materialized flow emits.
    Notice the first `n` elements are buffered internally before materializing the flow, This flow will then be materialized and connected to the rest of the upstream - producing elements at its own discretion (might 'swallow' or multiply elements).

**backpressures** when the materialized flow backpressures

**completes**  the materialized flow completes.
    If upstream completes before producing `n` elements, `f` will be applied with the provided elements,
    the resulting flow will be materialized and signalled for upstream completion, it can then cancel/complete at its own discretion.


@@@


