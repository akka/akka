# Source.zipN

Combine the elements of multiple sources into a source of sequences of value.

@ref[Source operators](../index.md#source-operators)

## Signature

@apidoc[Source.zipN](Source$) { scala="#zipN[T](sources:scala.collection.immutable.Seq[akka.stream.scaladsl.Source[T,_]]):akka.stream.scaladsl.Source[scala.collection.immutable.Seq[T],akka.NotUsed]" java="#zipN(java.util.List)" } 

## Description

Collects one element for every upstream and when all upstreams has emitted one element all of them are emitted downstream as a collection.
The element order in the downstream collection will be the same order as the sources were listed.

Since the sources are provided as a list the individual types are lost and @scala[the downstream sequences will end up containing the closest supertype shared by all sources]@java[you may have to make sure to have sources type casted to the same common supertype of all stream elements to use `zipN`].

See also:

 * @ref:[zipWithN](zipWithN.md)
 * @ref:[zip](../Source-or-Flow/zip.md)
 * @ref:[zipAll](../Source-or-Flow/zipAll.md)
 * @ref:[zipWith](../Source-or-Flow/zipWith.md)  
 * @ref:[zipWithIndex](../Source-or-Flow/zipWithIndex.md)  

## Example

In this sample we zip a stream of characters, a stream of numbers and a stream of colours. Into a single `Source`
where each element is a @scala[`Vector`]@java[`List`] of `[character, number, color]`:

Scala
:   @@snip [Zip.scala](/akka-docs/src/test/scala/docs/stream/operators/source/Zip.scala) { #zipN-simple }

Java
:   @@snip [Zip.java](/akka-docs/src/test/java/jdocs/stream/operators/source/Zip.java) { #zipN-simple }

Note how it stops as soon as any of the original sources reaches its end.

## Reactive Streams semantics

@@@div { .callout }

**emits** when all of the inputs has an element available

**completes** when any upstream completes

**backpressures** all upstreams when downstream backpressures but also on an upstream that has emitted an element until all other upstreams has emitted elements

@@@

