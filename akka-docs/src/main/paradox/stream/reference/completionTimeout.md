# completionTimeout

If the completion of the stream does not happen until the provided timeout, the stream is failed
with a `TimeoutException`.

## Signature

## Description

If the completion of the stream does not happen until the provided timeout, the stream is failed
with a `TimeoutException`.


@@@div { .callout }

**emits** when upstream emits an element

**backpressures** when downstream backpressures

**completes** when upstream completes or fails if timeout elapses before upstream completes

**cancels** when downstream cancels

@@@

## Example

