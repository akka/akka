# zipWithIndex

Zips elements of current flow with its indices.

@ref[Fan-in operators](../index.md#fan-in-operators)

## Signature

@apidoc[Source.zipWithIndex](Source) { scala="#zipWithIndex:FlowOps.this.Repr[(Out,Long)]" java="#zipWithIndex()" }
@apidoc[Flow.zipWithIndex](Flow) { scala="#zipWithIndex:FlowOps.this.Repr[(Out,Long)]" java="#zipWithIndex()" }

## Description

Zips elements of current flow with its indices.

See also:

 * @ref:[zip](zip.md)
 * @ref:[zipAll](zipAll.md)
 * @ref:[zipWith](zipWith.md)  

## Example

Scala
:   @@snip [FlowZipWithIndexSpec.scala](/akka-stream-tests/src/test/scala/akka/stream/scaladsl/FlowZipWithIndexSpec.scala) { #zip-with-index }

Java
:   @@snip [SourceOrFlow.java](/akka-docs/src/test/java/jdocs/stream/operators/SourceOrFlow.java) { #zip-with-index }

## Reactive Streams semantics

@@@div { .callout }

**emits** upstream emits an element and is paired with their index

**backpressures** when downstream backpressures

**completes** when upstream completes

@@@
