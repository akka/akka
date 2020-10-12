# delayWith

Delay every element passed through with a duration that can be controlled dynamically.

@ref[Timer driven operators](../index.md#timer-driven-operators)

## Signature

@apidoc[Source.delayWith](Source) { scala="#delayWith(delayStrategySupplier:()=&gt;akka.stream.scaladsl.DelayStrategy[Out],overFlowStrategy:akka.stream.DelayOverflowStrategy):FlowOps.this.Repr[Out]" java="#delayWith(java.util.function.Supplier,akka.stream.DelayOverflowStrategy)" }
@apidoc[Flow.delayWith](Flow) { scala="#delayWith(delayStrategySupplier:()=&gt;akka.stream.scaladsl.DelayStrategy[Out],overFlowStrategy:akka.stream.DelayOverflowStrategy):FlowOps.this.Repr[Out]" java="#delayWith(java.util.function.Supplier,akka.stream.DelayOverflowStrategy)" }


## Description

Delay every element passed through with a duration that can be controlled dynamically, individually for each elements (via the `DelayStrategy`).


@@@div { .callout }

**emits** there is a pending element in the buffer and configured time for this element elapsed

**backpressures** differs, depends on `OverflowStrategy` set

**completes** when upstream completes and buffered elements has been drained


@@@

