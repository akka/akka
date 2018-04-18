# reduce

Start with first element and then apply the current and next value to the given function, when upstream
complete the current value is emitted downstream.

## Signature

## Description

Start with first element and then apply the current and next value to the given function, when upstream
complete the current value is emitted downstream. Similar to `fold`.


@@@div { .callout }

**emits** when upstream completes

**backpressures** when downstream backpressures

**completes** when upstream completes

@@@

## Example

