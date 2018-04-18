# headOption

Materializes into a @scala[`Future[Option[T]]`] @java[`CompletionStage<Optional<T>>`] which completes with the first value arriving wrapped in @scala[`Some`] @java[`Optional`],
or @scala[a `None`] @java[an empty Optional] if the stream completes without any elements emitted.

## Signature

## Description

Materializes into a @scala[`Future[Option[T]]`] @java[`CompletionStage<Optional<T>>`] which completes with the first value arriving wrapped in @scala[`Some`] @java[`Optional`],
or @scala[a `None`] @java[an empty Optional] if the stream completes without any elements emitted.


@@@div { .callout }

**cancels** after receiving one element

**backpressures** never

@@@

## Example

