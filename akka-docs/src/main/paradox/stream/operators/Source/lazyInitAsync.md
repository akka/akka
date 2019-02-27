# Source.lazyInitAsync

Defers creation and materialization of a `Source` until there is demand.

@ref[Source operators](../index.md#source-operators)

@@@div { .group-scala }

## Signature

@@signature [Source.scala](/akka-stream/src/main/scala/akka/stream/scaladsl/Source.scala) { #lazyInitAsync }

@@@

## Description

Creates a real `Source` upon the first demand by calling the given `sourcefactory`.
The real `Source` will not be created if there is no demand.

The materialized value of the `Source` is a @scala[`Future[Option[M]]`]@java[`CompletionStage<Optional<M>>`] that is 
completed with @scala[`Some(mat)`]@java[`Optional.of(mat)`] when the real source gets materialized or with @scala[`None`]
@java[an empty optional] when there is no demand prior to cancellation. If the source materialization (including the call of the `sourcefactory`) 
fails then the future is completed with the failure.


@@@div { .callout }

**emits** depends on the wrapped `Source`

**completes** depends on the wrapped `Source`

@@@

