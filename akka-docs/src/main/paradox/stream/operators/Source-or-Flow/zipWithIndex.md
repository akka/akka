# zipWithIndex

Zips elements of current flow with its indices.

@ref[Fan-in operators](../index.md#fan-in-operators)

@@@div { .group-scala }

## Signature

@@signature [Flow.scala](/akka-stream/src/main/scala/akka/stream/scaladsl/Flow.scala) { #zipWithIndex }

@@@

## Description

Zips elements of current flow with its indices.

## Reactive Streams semantics

@@@div { .callout }

**emits** upstream emits an element and is paired with their index

**backpressures** when downstream backpressures

**completes** when upstream completes

@@@

## Example

Scala
:   @@snip [FlowZipWithIndexSpec.scala](/akka-stream-tests/src/test/scala/akka/stream/scaladsl/FlowZipWithIndexSpec.scala) { #zip-with-index }

Java
:   @@snip [SourceOrFlow.java](/akka-docs/src/test/java/jdocs/stream/operators/SourceOrFlow.java) { #zip-with-index }
