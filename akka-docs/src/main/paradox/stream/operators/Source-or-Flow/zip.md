# zip

Combines elements from each of multiple sources into @scala[tuples] @java[*Pair*] and passes the @scala[tuples] @java[pairs] downstream.

@ref[Fan-in operators](../index.md#fan-in-operators)

@@@div { .group-scala }

## Signature

@@signature [Flow.scala](/akka-stream/src/main/scala/akka/stream/scaladsl/Flow.scala) { #zip }

@@@

## Description

Combines elements from each of multiple sources into @scala[tuples] @java[*Pair*] and passes the @scala[tuples] @java[pairs] downstream.

See also:

 * @ref:[zipAll](zipAll.md)
 * @ref:[zipWith](zipWith.md)
 * @ref:[zipWithIndex](zipWithIndex.md)  

## Examples

Scala
:   @@snip [FlowZipSpec.scala](/akka-stream-tests/src/test/scala/akka/stream/scaladsl/FlowZipSpec.scala) { #zip }

Java
:   @@snip [SourceOrFlow.java](/akka-docs/src/test/java/jdocs/stream/operators/SourceOrFlow.java) { #zip }

## Reactive Streams semantics

@@@div { .callout }

**emits** when both of the inputs have an element available

**backpressures** both upstreams when downstream backpressures but also on an upstream that has emitted an element until the other upstream has emitted an element

**completes** when either upstream completes

@@@
