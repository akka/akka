# fromFutureSource

`fromFutureSource` has been deprecated in 2.6.0, use `Source.futureSource` instead.

@ref[Source operators](../index.md#source-operators)

@@@div { .group-scala }

## Signature

@@signature [Source.scala](/akka-stream/src/main/scala/akka/stream/scaladsl/Source.scala) { #fromFutureSource }

@@@

## Description

`fromFutureSource` has been deprecated in 2.6.0, use @ref:[futureSource](futureSource.md) instead.

Streams the elements of the given future source once it successfully completes. 
If the future fails the stream is failed.

## Reactive Streams semantics

@@@div { .callout }

**emits** the next value from the *future* source, once it has completed

**completes** after the *future* source completes

@@@

