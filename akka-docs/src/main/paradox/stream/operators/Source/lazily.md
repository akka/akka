# lazily

`lazily` has been deprecated in 2.6.0, use `Source.lazySource` instead.

@ref[Source operators](../index.md#source-operators)

@@@div { .group-scala }

## Signature

@@signature [Source.scala](/akka-stream/src/main/scala/akka/stream/scaladsl/Source.scala) { #lazily }

@@@

## Description

`lazily` has been deprecated in 2.6.0, use @ref:[lazySource](lazySource.md) instead.

Defers creation and materialization of a `Source` until there is demand.

## Reactive Streams semantics

@@@div { .callout }

**emits** depends on the wrapped `Source`

**completes** depends on the wrapped `Source`

@@@

