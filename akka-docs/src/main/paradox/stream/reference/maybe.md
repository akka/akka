# maybe

Materialize a @scala[`Promise[Option[T]]`] @java[`CompletionStage`] that if completed with a @scala[`Some[T]`] @java[`Optional`] 
will emit that *T* and then complete the stream, or if completed with @scala[`None`] @java[`empty Optional`] complete the stream right away.

## Signature

## Description

Materialize a @scala[`Promise[Option[T]]`] @java[`CompletionStage`] that if completed with a @scala[`Some[T]`] @java[`Optional`] 
will emit that *T* and then complete the stream, or if completed with @scala[`None`] @java[`empty Optional`] complete the stream right away.


@@@div { .callout }

**emits** when the returned promise is completed with some value

**completes** after emitting some value, or directly if the promise is completed with no value

@@@

## Example

