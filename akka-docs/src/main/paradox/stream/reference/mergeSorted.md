# mergeSorted

Merge multiple sources.

## Signature

## Description

Merge multiple sources. Waits for one element to be ready from each input stream and emits the
smallest element.


@@@div { .callout }

**emits** when all of the inputs have an element available

**backpressures** when downstream backpressures

**completes** when all upstreams complete

@@@

## Example

