# prepend

Prepends the given source to the flow, consuming it until completion before the original source is consumed.

## Signature

## Description

Prepends the given source to the flow, consuming it until completion before the original source is consumed.

If materialized values needs to be collected `prependMat` is available.


@@@div { .callout }

**emits** when the given stream has an element available; if the given input completes, it tries the current one

**backpressures** when downstream backpressures

**completes** when all upstreams complete

@@@

## Example

