# map

Transform each element in the stream by calling a mapping function with it and passing the returned value downstream.

## Signature

## Description

Transform each element in the stream by calling a mapping function with it and passing the returned value downstream.


@@@div { .callout }

**emits** when the mapping function returns an element

**backpressures** when downstream backpressures

**completes** when upstream completes

@@@

## Example

