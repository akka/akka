# mergeSorted

Merge multiple sources.

@ref[Fan-in operators](../index.md#fan-in-operators)

@@@div { .group-scala }

## Signature

@@signature [Flow.scala](/akka-stream/src/main/scala/akka/stream/scaladsl/Flow.scala) { #mergeSorted }

@@@

## Description

Merge multiple sources. Waits for one element to be ready from each input stream and emits the
smallest element.

## Reactive Streams semantics

@@@div { .callout }

**emits** when all of the inputs have an element available

**backpressures** when downstream backpressures

**completes** when all upstreams complete

@@@


## Example
Scala
:   @@snip [FlowMergeSpec.scala](/akka-stream-tests/src/test/scala/akka/stream/scaladsl/FlowMergeSpec.scala) { #merge-sorted }

Java
:   @@snip [SourceOrFlow.java](/akka-docs/src/test/java/jdocs/stream/operators/SourceOrFlow.java) { #merge-sorted }