# filterNot

Filter the incoming elements using a predicate.

## Signature

## Description

Filter the incoming elements using a predicate. If the predicate returns false the element is passed downstream, if
it returns true the element is discarded.


@@@div { .callout }

**emits** when the given predicate returns false for the element

**backpressures** when the given predicate returns false for the element and downstream backpressures

**completes** when upstream completes

@@@

## Example

