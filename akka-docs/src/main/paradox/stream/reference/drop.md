# drop

Drop `n` elements and then pass any subsequent element downstream.

## Signature

## Description

Drop `n` elements and then pass any subsequent element downstream.


@@@div { .callout }

**emits** when the specified number of elements has been dropped already

**backpressures** when the specified number of elements has been dropped and downstream backpressures

**completes** when upstream completes

@@@

## Example

