# limitWeighted

Ensure stream boundedness by evaluating the cost of incoming elements using a cost function.

## Signature

## Description

Ensure stream boundedness by evaluating the cost of incoming elements using a cost function.
Evaluated cost of each element defines how many elements will be allowed to travel downstream.


@@@div { .callout }

**emits** when upstream emits and the number of emitted elements has not reached max

**backpressures** when downstream backpressures

**completes** when upstream completes and the number of emitted elements has not reached max

@@@

## Example

