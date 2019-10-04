# Partition

Fan-out the stream to several streams.

@ref[Fan-out operators](index.md#fan-out-operators)

## Signature

## Description

Fan-out the stream to several streams. Each upstream element is emitted to one downstream consumer according to the
partitioner function applied to the element.

## Reactive Streams semantics

@@@div { .callout }

**emits** when the chosen output stops backpressuring and there is an input element available

**backpressures** when the chosen output backpressures

**completes** when upstream completes and no output is pending

**cancels** depends on the `eagerCancel` flag. If it is true, when any downstream cancels, if false, when all downstreams cancel.

@@@

