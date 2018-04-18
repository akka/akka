# Sink.ignore

Consume all elements but discards them.

@ref[Sink stages](../index.md#sink-stages)

@@@div { .group-scala }

## Signature

@@signature [Sink.scala]($akka$/akka-stream/src/main/scala/akka/stream/scaladsl/Sink.scala) { #ignore }

@@@

## Description

Consume all elements but discards them. Useful when a stream has to be consumed but there is no use to actually
do anything with the elements.


@@@div { .callout }

**cancels** never

**backpressures** never

@@@


