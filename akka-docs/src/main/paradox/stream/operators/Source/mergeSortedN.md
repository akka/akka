# Source.mergeSortedN

Combine the elements of multiple pre-sorted streams into a single sorted stream.

@ref[Source operators](../index.md#source-operators)

@@@div { .group-scala }

## Signature

@@signature [Source.scala](/akka-stream/src/main/scala/akka/stream/scaladsl/Source.scala) { #mergeSortedN }

@@@

## Description

Combine the elements of multiple pre-sorted streams into a single sorted stream.


@@@div { .callout }

**emits** when all of the inputs has an element available

**completes** when all upstream completes

@@@

