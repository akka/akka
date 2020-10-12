# zipWith

Combines elements from multiple sources through a `combine` function and passes the returned value downstream.

@ref[Fan-in operators](../index.md#fan-in-operators)

## Signature

@apidoc[Source.zipWith](Source) { scala="#zipWith[Out2,Out3](that:akka.stream.Graph[akka.stream.SourceShape[Out2],_])(combine:(Out,Out2)=&gt;Out3):FlowOps.this.Repr[Out3]" java="#zipWith(akka.stream.Graph,akka.japi.function.Function2)" }
@apidoc[Flow.zipWith](Flow) { scala="#zipWith[Out2,Out3](that:akka.stream.Graph[akka.stream.SourceShape[Out2],_])(combine:(Out,Out2)=&gt;Out3):FlowOps.this.Repr[Out3]" java="#zipWith(akka.stream.Graph,akka.japi.function.Function2)" }


## Description

Combines elements from multiple sources through a `combine` function and passes the
returned value downstream.

See also:

 * @ref:[zip](zip.md)
 * @ref:[zipAll](zipAll.md)
 * @ref:[zipWithIndex](zipWithIndex.md)

## Examples

Scala
:   @@snip [FlowZipWithSpec.scala](/akka-stream-tests/src/test/scala/akka/stream/scaladsl/FlowZipWithSpec.scala) { #zip-with }

Java
:   @@snip [SourceOrFlow.java](/akka-docs/src/test/java/jdocs/stream/operators/SourceOrFlow.java) { #zip-with }

## Reactive Streams semantics

@@@div { .callout }

**emits** when all of the inputs have an element available

**backpressures** both upstreams when downstream backpressures but also on an upstream that has emitted an element until the other upstream has emitted an element

**completes** when any upstream completes

@@@
