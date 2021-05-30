# Source.zipWithN

Combine the elements of multiple streams into a stream of sequences using a combiner function.

@ref[Source operators](../index.md#source-operators)

## Signature

@apidoc[Source.zipWithN](Source$) { scala="#zipWithN[T,O](zipper:scala.collection.immutable.Seq[T]=&gt;O)(sources:scala.collection.immutable.Seq[akka.stream.scaladsl.Source[T,_]]):akka.stream.scaladsl.Source[O,akka.NotUsed]" java="#zipWithN(akka.japi.function.Function,java.util.List)" }

## Description

Combine the elements of multiple streams into a stream of sequences using a combiner function.

This operator is essentially the same as using @ref:[zipN](zipN.md) followed by @ref[map](../Source-or-Flow/map.md)
to turn the zipped sequence into an arbitrary object to emit downstream.

See also:

 * @ref:[zipN](zipN.md)
 * @ref:[zip](../Source-or-Flow/zip.md)
 * @ref:[zipAll](../Source-or-Flow/zipAll.md)
 * @ref:[zipWith](../Source-or-Flow/zipWith.md)  
 * @ref:[zipWithIndex](../Source-or-Flow/zipWithIndex.md)  

## Example

In this sample we zip three streams of integers and for each zipped sequence of numbers we calculate the max value
and send downstream:

Scala
:   @@snip [Zip.scala](/akka-docs/src/test/scala/docs/stream/operators/source/Zip.scala) { #zipWithN-simple }

Java
:   @@snip [Zip.java](/akka-docs/src/test/java/jdocs/stream/operators/source/Zip.java) { #zipWithN-simple }

Note how it stops as soon as any of the original sources reaches its end.

## Reactive Streams semantics

@@@div { .callout }

**emits** when all of the inputs has an element available

**completes** when any upstream completes

**backpressures** all upstreams when downstream backpressures but also on an upstream that has emitted an element until all other upstreams has emitted elements

@@@


