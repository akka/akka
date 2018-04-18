# merge

Merge multiple sources.

## Signature

## Description

Merge multiple sources. Picks elements randomly if all sources has elements ready.


@@@div { .callout }

**emits** when one of the inputs has an element available

**backpressures** when downstream backpressures

**completes** when all upstreams complete (This behavior is changeable to completing when any upstream completes by setting `eagerComplete=true`.)

@@@

## Example

