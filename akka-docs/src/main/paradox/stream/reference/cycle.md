# cycle

Stream iterator in cycled manner.

## Signature

## Description

Stream iterator in cycled manner. Internally new iterator is being created to cycle the one provided via argument meaning
when original iterator runs out of elements process will start all over again from the beginning of the iterator
provided by the evaluation of provided parameter. If method argument provides empty iterator stream will be terminated with
exception.


@@@div { .callout }

**emits** the next value returned from cycled iterator

**completes** never

@@@

## Example

