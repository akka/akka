# Sink.ignore

Consume all elements but discards them.

@ref[Sink operators](../index.md#sink-operators)

@@@div { .group-scala }

## Signature

@@signature [Sink.scala](/akka-stream/src/main/scala/akka/stream/scaladsl/Sink.scala) { #ignore }

@@@

## Description

Consume all elements but discards them. Useful when a stream has to be consumed but there is no use to actually
do anything with the elements.

## Reactive Streams semantics

@@@div { .callout }

**cancels** never

**backpressures** never

@@@


