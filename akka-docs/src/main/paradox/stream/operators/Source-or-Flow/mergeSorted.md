# mergeSorted

Merge multiple sources.

@ref[Fan-in operators](../index.md#fan-in-operators)

@@@div { .group-scala }

## Signature

@@signature [Flow.scala]($akka$/akka-stream/src/main/scala/akka/stream/scaladsl/Flow.scala) { #mergeSorted }

@@@

## Description

Merge multiple sources. Waits for one element to be ready from each input stream and emits the
smallest element.


@@@div { .callout }

**emits** when all of the inputs have an element available

**backpressures** when downstream backpressures

**completes** when all upstreams complete

@@@

