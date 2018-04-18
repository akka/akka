# scan

Emit its current value which starts at `zero` and then applies the current and next value to the given function
emitting the next current value.

## Signature

## Description

Emit its current value which starts at `zero` and then applies the current and next value to the given function
emitting the next current value.

Note that this means that scan emits one element downstream before and upstream elements will not be requested until
the second element is required from downstream.


@@@div { .callout }

**emits** when the function scanning the element returns a new element

**backpressures** when downstream backpressures

**completes** when upstream completes

@@@

## Example

