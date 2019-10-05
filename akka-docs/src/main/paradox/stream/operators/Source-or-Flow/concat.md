# concat

After completion of the original upstream the elements of the given source will be emitted.

@ref[Fan-in operators](../index.md#fan-in-operators)

@@@div { .group-scala }

## Signature

@@signature [Flow.scala](/akka-stream/src/main/scala/akka/stream/scaladsl/Flow.scala) { #concat }

@@@

## Description

After completion of the original upstream the elements of the given source will be emitted.

## Reactive Streams semantics

@@@div { .callout }

**emits** when the current stream has an element available; if the current input completes, it tries the next one

**backpressures** when downstream backpressures

**completes** when all upstreams complete

@@@


## Example
Scala
:   @@snip [FlowConcatSpec.scala](/akka-stream-tests/src/test/scala/akka/stream/scaladsl/FlowConcatSpec.scala) { #concat }

Java
:   @@snip [SourceOrFlow.java](/akka-docs/src/test/java/jdocs/stream/operators/SourceOrFlow.java) { #concat }