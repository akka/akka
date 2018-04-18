# buffer (Drop)

Allow for a temporarily faster upstream events by buffering `size` elements.

## Signature

## Description

Allow for a temporarily faster upstream events by buffering `size` elements. When the buffer is full elements are
dropped according to the specified `OverflowStrategy`:

 * `dropHead` drops the oldest element in the buffer to make space for the new element
 * `dropTail` drops the youngest element in the buffer to make space for the new element
 * `dropBuffer` drops the entire buffer and buffers the new element
 * `dropNew` drops the new element


@@@div { .callout }

**emits** when downstream stops backpressuring and there is a pending element in the buffer

**backpressures** never (when dropping cannot keep up with incoming elements)

**completes** upstream completes and buffered elements has been drained

@@@

## Example

