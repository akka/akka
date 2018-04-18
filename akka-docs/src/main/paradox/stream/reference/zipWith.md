# zipWith

Combines elements from multiple sources through a `combine` function and passes the
returned value downstream.

## Signature

## Description

Combines elements from multiple sources through a `combine` function and passes the
returned value downstream.


@@@div { .callout }

**emits** when all of the inputs have an element available

**backpressures** when downstream backpressures

**completes** when any upstream completes

@@@

## Example

