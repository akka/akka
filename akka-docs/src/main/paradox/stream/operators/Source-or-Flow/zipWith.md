# zipWith

Combines elements from multiple sources through a `combine` function and passes the returned value downstream.

@ref[Fan-in operators](../index.md#fan-in-operators)

@@@div { .group-scala }

## Signature

@@signature [Flow.scala](/akka-stream/src/main/scala/akka/stream/scaladsl/Flow.scala) { #zipWith }

@@@

## Description

Combines elements from multiple sources through a `combine` function and passes the
returned value downstream.

## Reactive Streams semantics

@@@div { .callout }

**emits** when all of the inputs have an element available

**backpressures** when downstream backpressures

**completes** when any upstream completes

@@@


## Example
Scala
:   @@snip [FlowZipWithSpec.scala](/akka-stream-tests/src/test/scala/akka/stream/scaladsl/FlowZipWithSpec.scala) { #zip-with }

Java
:   @@snip [SourceOrFlow.java](/akka-docs/src/test/java/jdocs/stream/operators/SourceOrFlow.java) { #zip-with }