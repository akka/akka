# Source.zipN

Combine the elements of multiple streams into a stream of sequences.

@ref[Source operators](../index.md#source-operators)

@@@div { .group-scala }

## Signature

@@signature [Source.scala](/akka-stream/src/main/scala/akka/stream/scaladsl/Source.scala) { #zipN }

@@@

## Description

Combine the elements of multiple streams into a stream of sequences.

## Reactive Streams semantics

@@@div { .callout }

**emits** when all of the inputs has an element available

**completes** when any upstream completes

@@@

