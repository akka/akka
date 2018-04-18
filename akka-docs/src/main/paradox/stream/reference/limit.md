# limit

Limit number of element from upstream to given `max` number.

## Signature

## Description

Limit number of element from upstream to given `max` number.


@@@div { .callout }

**emits** when upstream emits and the number of emitted elements has not reached max

**backpressures** when downstream backpressures

**completes** when upstream completes and the number of emitted elements has not reached max

@@@

## Example

