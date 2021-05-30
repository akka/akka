# flatMapPrefix

Use the first `n` elements from the stream to determine how to process the rest.

@ref[Nesting and flattening operators](../index.md#nesting-and-flattening-operators)

## Signature

@apidoc[Source.flatMapPrefix](Source) { scala="#flatMapPrefix[Out2,Mat2](n:Int)(f:scala.collection.immutable.Seq[Out]=&gt;akka.stream.scaladsl.Flow[Out,Out2,Mat2]):FlowOps.this.Repr[Out2]" java="#flatMapPrefix(int,akka.japi.function.Function)" }
@apidoc[Flow.flatMapPrefix](Flow) { scala="#flatMapPrefix[Out2,Mat2](n:Int)(f:scala.collection.immutable.Seq[Out]=&gt;akka.stream.scaladsl.Flow[Out,Out2,Mat2]):FlowOps.this.Repr[Out2]" java="#flatMapPrefix(int,akka.japi.function.Function)" }


## Description

Take up to *n* elements from the stream (less than *n* only if the upstream completes before emitting *n* elements),
then apply *f* on these elements in order to obtain a flow, this flow is then materialized and the rest of the input is processed by this flow (similar to via).
This method returns a flow consuming the rest of the stream producing the materialized flow's output.

## Reactive Streams semantics

@@@div { .callout }

**emits** when the materialized flow emits.
    Notice the first `n` elements are buffered internally before materializing the flow and connecting it to the rest of the upstream - producing elements at its own discretion (might 'swallow' or multiply elements).

**backpressures** when the materialized flow backpressures

**completes**  the materialized flow completes.
    If upstream completes before producing `n` elements, `f` will be applied with the provided elements,
    the resulting flow will be materialized and signalled for upstream completion, it can then or continue to emit elements at its own discretion.


@@@


