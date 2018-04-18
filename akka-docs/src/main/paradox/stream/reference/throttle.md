# throttle

Limit the throughput to a specific number of elements per time unit, or a specific total cost per time unit, where
a function has to be provided to calculate the individual cost of each element.

## Signature

## Description

Limit the throughput to a specific number of elements per time unit, or a specific total cost per time unit, where
a function has to be provided to calculate the individual cost of each element.


@@@div { .callout }

**emits** when upstream emits an element and configured time per each element elapsed

**backpressures** when downstream backpressures

**completes** when upstream completes

@@@

## Example

