# buffer

Allow for a temporarily faster upstream events by buffering `size` elements.

@ref[Backpressure aware operators](../index.md#backpressure-aware-operators)

@@@ div { .group-scala }
## Signature

@@signature [Flow.scala](/akka-stream/src/main/scala/akka/stream/scaladsl/Flow.scala) { #buffer }
@@@


## Description

Allow for a temporarily faster upstream events by buffering `size` elements. When the buffer is full, a new element is
handled according to the specified `OverflowStrategy`:

 * `backpressure` backpressure is applied upstream
 * `dropHead` drops the oldest element in the buffer to make space for the new element
 * `dropTail` drops the youngest element in the buffer to make space for the new element
 * `dropBuffer` drops the entire buffer and buffers the new element
 * `dropNew` drops the new element
 * `fail` fails the flow with a `BufferOverflowException`

## Reactive Streams semantics

@@@div { .callout }

**emits** when downstream stops backpressuring and there is a pending element in the buffer

**backpressures** when `OverflowStrategy` is `backpressure` and buffer is full

**completes** when upstream completes and buffered elements has been drained, or when `OverflowStrategy` is `fail`, the buffer is full and a new element arrives

@@@


