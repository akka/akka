# fromSourceCompletionStage

Streams the elements of an asynchronous source once its given *completion* operator completes.

@ref[Source operators](../index.md#source-operators)

## Signature

## Description

Streams the elements of an asynchronous source once its given *completion* operator completes.
If the *completion* fails the stream is failed with that exception.


@@@div { .callout }

**emits** the next value from the asynchronous source, once its *completion operator* has completed

**completes** after the asynchronous source completes

@@@

