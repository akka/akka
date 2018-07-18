# Flow.lazyInitAsync

Creates a real `Flow` upon receiving the first element by calling relevant `flowFactory` given as an argument.

@ref[Simple operators](../index.md#simple-operators)

@@@div { .group-scala }

## Signature

@@signature [Flow.scala]($akka$/akka-stream/src/main/scala/akka/stream/scaladsl/Flow.scala) { #lazyInitAsync }

@@@

## Description

Creates a real `Flow` upon receiving the first element by calling relevant `flowFactory` given as an argument.
Internal `Flow` will not be created if there are no elements, because of completion or error.
The materialized value of the `Flow` will be the materialized value of the created internal flow.

The materialized value of the `Flow` is a @scala[`Future[Option[M]]`]@java[`CompletionStage<Optional<M>>`] that is 
completed with @scala[`Some(mat)`]@java[`Optional.of(mat)`] when the internal flow gets materialized or with @scala[`None`]
@java[an empty optional] when there where no elements. If the flow materialization (including the call of the `flowFactory`) 
fails then the future is completed with a failure.

Adheres to the @scala[@scaladoc[`ActorAttributes.SupervisionStrategy`](akka.stream.ActorAttributes$$SupervisionStrategy)]
@java[`ActorAttributes.SupervisionStrategy`] attribute.


@@@div { .callout }

**emits** when the internal flow is successfully created and it emits

**backpressures** when the internal flow is successfully created and it backpressures

**completes** when upstream completes and all elements have been emitted from the internal flow

**completes** when upstream completes and all futures have been completed and all elements have been emitted

@@@

