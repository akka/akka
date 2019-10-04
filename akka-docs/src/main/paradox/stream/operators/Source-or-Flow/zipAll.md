# zipAll

Combines all elements from each of multiple sources into @scala[tuples] @java[*Pair*] and passes the @scala[tuples] @java[pairs] downstream.

@ref[Fan-in operators](../index.md#fan-in-operators)

@@@div { .group-scala }

## Signature

@@signature [Flow.scala](/akka-stream/src/main/scala/akka/stream/scaladsl/Flow.scala) { #zipAll }

@@@

## Description

Combines all elements from each of multiple sources into @scala[tuples] @java[*Pair*] and passes the @scala[tuples] @java[pairs] downstream.

## Reactive Streams semantics

@@@div { .callout }

**emits** at first emits when both inputs emit, and then as long as any input emits (coupled to the default value of the completed input).

**backpressures** when downstream backpressures

**completes** when all upstream completes

@@@

## Example
Scala
:   @@snip [FlowZipSpec.scala](/akka-stream-tests/src/test/scala/akka/stream/scaladsl/FlowZipSpec.scala) { #zip }

Java
:   @@snip [SourceOrFlow.java](/akka-docs/src/test/java/jdocs/stream/operators/SourceOrFlow.java) { #zip }