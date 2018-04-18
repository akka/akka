# flatMapMerge

Transform each input element into a `Source` whose elements are then flattened into the output stream through
merging.

## Signature

## Description

Transform each input element into a `Source` whose elements are then flattened into the output stream through
merging. The maximum number of merged sources has to be specified.


@@@div { .callout }

**emits** when one of the currently consumed substreams has an element available

**backpressures** when downstream backpressures

**completes** when upstream completes and all consumed substreams complete

@@@

## Example

