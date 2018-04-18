# buffer (Backpressure)

Allow for a temporarily faster upstream events by buffering `size` elements.

## Signature

## Description

Allow for a temporarily faster upstream events by buffering `size` elements. When the buffer is full backpressure
is applied.


@@@div { .callout }

**emits** when downstream stops backpressuring and there is a pending element in the buffer

**backpressures** when buffer is full

**completes** when upstream completes and buffered elements has been drained

@@@

## Example

