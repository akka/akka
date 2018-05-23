# from

Stream the values of an `Iterable`.

@ref[Source operators](../index.md#source-operators)

## Description

Stream the values of an `Iterable`. Make sure the `Iterable` is immutable or at least not modified after being used
as a source.

@@@div { .callout }

**emits** the next value of the seq

**completes** when the last element of the seq has been emitted

@@@

