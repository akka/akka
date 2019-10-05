# Unzip

Takes a stream of two element tuples and unzips the two elements ino two different downstreams.

@ref[Fan-out operators](index.md#fan-out-operators)

## Signature

## Description

Takes a stream of two element tuples and unzips the two elements ino two different downstreams.

## Reactive Streams semantics

@@@div { .callout }

**emits** when all of the outputs stops backpressuring and there is an input element available

**backpressures** when any of the outputs backpressures

**completes** when upstream completes

@@@

