# lazyInitAsync

Defers creation and materialization of a `Source` until there is demand.

@ref[Source operators](../index.md#source-operators)

## Signature

@@signature [Source.scala](/akka-stream/src/main/scala/akka/stream/scaladsl/Source.scala) { #lazyInitAsync }

@@@

## Description

Defers creation and materialization of a `Source` until there is demand.


@@@div { .callout }

**emits** depends on the wrapped `Source`

**completes** depends on the wrapped `Source`

@@@

