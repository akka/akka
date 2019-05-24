# Broadcast

Emit each incoming element each of `n` outputs.

@ref[Fan-out operators](index.md#fan-out-operators)

## Description

Emit each incoming element each of `n` outputs.


@@@div { .callout }

**emits** when all of the outputs stops backpressuring and there is an input element available

**backpressures** when any of the outputs backpressures

**completes** when upstream completes

**cancels** depends on the `eagerCancel` flag. If it is true, when any downstream cancels, if false, when all downstreams cancel.

@@@


