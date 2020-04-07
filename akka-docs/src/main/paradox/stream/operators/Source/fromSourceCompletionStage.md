# Source.fromSourceCompletionStage

Deprecated by @ref:[`Source.completionStageSource`](completionStageSource.md).

@ref[Source operators](../index.md#source-operators)

## Signature

## Description

`fromSourceCompletionStage` has been deprecated in 2.6.0, use @ref:[completionStageSource](completionStageSource.md) instead.

Streams the elements of an asynchronous source once its given *completion* operator completes.
If the *completion* fails the stream is failed with that exception.

## Reactive Streams semantics

@@@div { .callout }

**emits** the next value from the asynchronous source, once its *completion operator* has completed

**completes** after the asynchronous source completes

@@@

