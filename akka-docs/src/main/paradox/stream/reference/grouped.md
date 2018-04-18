# grouped

Accumulate incoming events until the specified number of elements have been accumulated and then pass the collection of
elements downstream.

## Signature

## Description

Accumulate incoming events until the specified number of elements have been accumulated and then pass the collection of
elements downstream.


@@@div { .callout }

**emits** when the specified number of elements has been accumulated or upstream completed

**backpressures** when a group has been assembled and downstream backpressures

**completes** when upstream completes

@@@

## Example

