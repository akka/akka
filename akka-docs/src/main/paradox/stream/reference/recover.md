# recover

Allow sending of one last element downstream when a failure has happened upstream.

## Signature

## Description

Allow sending of one last element downstream when a failure has happened upstream.

Throwing an exception inside `recover` _will_ be logged on ERROR level automatically.


@@@div { .callout }

**emits** when the element is available from the upstream or upstream is failed and pf returns an element

**backpressures** when downstream backpressures, not when failure happened

**completes** when upstream completes or upstream failed with exception pf can handle

@@@

## Example

