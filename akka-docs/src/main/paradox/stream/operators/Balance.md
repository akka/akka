# Balance

Fan-out the stream to several streams.

@ref[Fan-out operators](index.md#fan-out-operators)

## Description

Fan-out the stream to several streams. Each upstream element is emitted to the first available downstream consumer.

## Reactive Streams semantics

@@@div { .callout }

**emits** when any of the outputs stops backpressuring; emits the element to the first available output

**backpressures** when all of the outputs backpressure

**completes** when upstream completes

**cancels** depends on the `eagerCancel` flag. If it is true, when any downstream cancels, if false, when all downstreams cancel.

@@@

