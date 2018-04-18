# dropWhile

dropWhile

## Signature

## Description

Drop elements as long as a predicate function return true for the element


@@@div { .callout }

**emits** when the predicate returned false and for all following stream elements

**backpressures** predicate returned false and downstream backpressures

**completes** when upstream completes

@@@

## Example

