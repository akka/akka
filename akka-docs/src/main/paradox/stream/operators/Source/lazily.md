# lazily

Defers creation and materialization of a `Source` until there is demand.

@ref[Source operators](../index.md#source-operators)

@@@div { .group-scala }

## Signature

@@signature [Source.scala](/akka-stream/src/main/scala/akka/stream/scaladsl/Source.scala) { #lazily }

@@@

## Description

Defers creation and materialization of a `Source` until there is demand.

## Reactive Streams semantics

@@@div { .callout }

**emits** depends on the wrapped `Source`

**completes** depends on the wrapped `Source`

@@@

