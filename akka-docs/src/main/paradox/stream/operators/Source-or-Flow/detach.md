# detach

Detach upstream demand from downstream demand without detaching the stream rates.

@ref[Simple operators](../index.md#simple-operators)

## Signature

@apidoc[Source.detach](Source) { scala="#detach:FlowOps.this.Repr[Out]" java="#detach()" }
@apidoc[Flow.detach](Flow) { scala="#detach:FlowOps.this.Repr[Out]" java="#detach()" }

## Description

Detach upstream demand from downstream demand without detaching the stream rates.

## Reactive Streams semantics

@@@div { .callout }

**emits** when the upstream operators has emitted and there is demand

**backpressures** when downstream backpressures

**completes** when upstream completes

@@@

