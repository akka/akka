# interleave

Emits a specifiable number of elements from the original source, then from the provided source and repeats.

@ref[Fan-in operators](../index.md#fan-in-operators)

@@@div { .group-scala }

## Signature

@@signature [Flow.scala](/akka-stream/src/main/scala/akka/stream/scaladsl/Flow.scala) { #interleave }

@@@

## Description

Emits a specifiable number of elements from the original source, then from the provided source and repeats. If one
source completes the rest of the other stream will be emitted.

## Reactive Streams semantics

@@@div { .callout }

**emits** when element is available from the currently consumed upstream

**backpressures** when upstream backpressures

**completes** when both upstreams have completed

@@@


## Example
Scala
:   @@snip [FlowInterleaveSpec.scala](/akka-stream-tests/src/test/scala/akka/stream/scaladsl/FlowInterleaveSpec.scala) { #interleave }

Java
:   @@snip [SourceOrFlow.java](/akka-docs/src/test/java/jdocs/stream/operators/SourceOrFlow.java) { #interleave }