# fromSourceCompletionStage

Streams the elements of an asynchronous source once its given *completion* stage completes.

## Signature

## Description

Streams the elements of an asynchronous source once its given *completion* stage completes.
If the *completion* fails the stream is failed with that exception.


@@@div { .callout }

**emits** the next value from the asynchronous source, once its *completion stage* has completed

**completes** after the asynchronous source completes

@@@

## Example

