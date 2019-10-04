# prepend

Prepends the given source to the flow, consuming it until completion before the original source is consumed.

@ref[Fan-in operators](../index.md#fan-in-operators)

@@@div { .group-scala }

## Signature

@@signature [Flow.scala](/akka-stream/src/main/scala/akka/stream/scaladsl/Flow.scala) { #prepend }

@@@

## Description

Prepends the given source to the flow, consuming it until completion before the original source is consumed.

If materialized values needs to be collected `prependMat` is available.

## Reactive Streams semantics

@@@div { .callout }

**emits** when the given stream has an element available; if the given input completes, it tries the current one

**backpressures** when downstream backpressures

**completes** when all upstreams complete

@@@


## Example
Scala
:   @@snip [FlowOrElseSpec.scala](/akka-stream-tests/src/test/scala/akka/stream/scaladsl/FlowPrependSpec.scala) { #prepend }

Java
:   @@snip [SourceOrFlow.java](/akka-docs/src/test/java/jdocs/stream/operators/SourceOrFlow.java) { #prepend }