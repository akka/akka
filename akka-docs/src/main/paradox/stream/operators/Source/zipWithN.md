# Source.zipWithN

Combine the elements of multiple streams into a stream of sequences using a combiner function.

@ref[Source operators](../index.md#source-operators)

@@@div { .group-scala }

## Signature

@@signature [Source.scala](/akka-stream/src/main/scala/akka/stream/scaladsl/Source.scala) { #zipWithN }

@@@

## Description

Combine the elements of multiple streams into a stream of sequences using a combiner function.

## Reactive Streams semantics

@@@div { .callout }

**emits** when all of the inputs has an element available

**completes** when any upstream completes

@@@


