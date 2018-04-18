# flatMapConcat

Transform each input element into a `Source` whose elements are then flattened into the output stream through
concatenation.

## Signature

## Description

Transform each input element into a `Source` whose elements are then flattened into the output stream through
concatenation. This means each source is fully consumed before consumption of the next source starts.


@@@div { .callout }

**emits** when the current consumed substream has an element available

**backpressures** when downstream backpressures

**completes** when upstream completes and all consumed substreams complete

@@@

## Example

