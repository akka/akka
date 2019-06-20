# zipAll

Combines all elements from each of multiple sources into @scala[tuples] @java[*Pair*] and passes the @scala[tuples] @java[pairs] downstream.

@ref[Fan-in operators](../index.md#fan-in-operators)

@@@div { .group-scala }

## Signature

@@signature [Flow.scala](/akka-stream/src/main/scala/akka/stream/scaladsl/Flow.scala) { #zipAll }

@@@

## Description

Combines all elements from each of multiple sources into @scala[tuples] @java[*Pair*] and passes the @scala[tuples] @java[pairs] downstream.


@@@div { .callout }

**emits** when all of the inputs have an element available, once part (but not all) of the inputs complete their values are substituted by the provided defaults.

**backpressures** when downstream backpressures

**completes** when all upstream completes

@@@

## Example
Scala
:   @@snip [FlowZipSpec.scala](/akka-stream-tests/src/test/scala/akka/stream/scaladsl/FlowZipSpec.scala) { #zip }

Java
:   @@snip [SourceOrFlow.java](/akka-docs/src/test/java/jdocs/stream/operators/SourceOrFlow.java) { #zip }