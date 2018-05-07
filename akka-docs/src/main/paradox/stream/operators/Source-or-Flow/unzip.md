# unzip

Takes a stream of two element tuples and unzips the two elements ino two different downstreams.

@ref[Fan-out stages](../index.md#fan-out-stages)

## Signature

## Description

Takes a stream of two element tuples and unzips the two elements ino two different downstreams.


@@@div { .callout }

**emits** when all of the outputs stops backpressuring and there is an input element available

**backpressures** when any of the outputs backpressures

**completes** when upstream completes

@@@

