# Source.repeat

Stream a single object repeatedly

@ref[Source operators](../index.md#source-operators)

@@@div { .group-scala }

## Signature

@@signature [Source.scala](/akka-stream/src/main/scala/akka/stream/scaladsl/Source.scala) { #repeat }

@@@

## Description

Stream a single object repeatedly

## Reactive Streams semantics

@@@div { .callout }

**emits** the same value repeatedly when there is demand

**completes** never

@@@

