# fromFuture

`fromFuture` has been deprecated in 2.6.0, use `Source.future` instead.

@ref[Source operators](../index.md#source-operators)

@@@div { .group-scala }

## Signature

@@signature [Source.scala](/akka-stream/src/main/scala/akka/stream/scaladsl/Source.scala) { #fromFuture }

@@@

## Description

`fromFuture` has been deprecated in 2.6.0, use @ref:[future](future.md) instead.

Send the single value of the `Future` when it completes and there is demand.
If the future fails the stream is failed with that exception.

## Reactive Streams semantics

@@@div { .callout }

**emits** the future completes

**completes** after the future has completed

@@@

## Example

