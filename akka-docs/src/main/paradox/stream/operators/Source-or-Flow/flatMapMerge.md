# flatMapMerge

Transform each input element into a `Source` whose elements are then flattened into the output stream through merging.

@ref[Nesting and flattening operators](../index.md#nesting-and-flattening-operators)

## Signature

@apidoc[Flow.flatMapMerge](Flow) { scala="#flatMapMerge[T,M](breadth:Int,f:Out=%3Eakka.stream.Graph[akka.stream.SourceShape[T],M]):FlowOps.this.Repr[T]" java="#flatMapMerge(int,akka.japi.function.Function)" } 

## Description

Transform each input element into a `Source` whose elements are then flattened into the output stream through
merging. The maximum number of merged sources has to be specified. When this is met `flatMapMerge` does not
request any more elements meaning that it back pressures until one of the existing `Source`s completes. 
Order of the elements for each `Source` is preserved but there is no deterministic order between elements from
different active `Source`s.

See also: @ref:[flatMapConcat](flatMapConcat.md)

## Example

In the following example `flatMapMerge` is used to create a `Source` for each incoming customerId. This could, for example,
be a calculation or a query to a database. There can be `breadth` active sources at any given time so
events for different customers could interleave in any order but events for the same customer will be in the
order emitted by the underlying `Source`;

Scala
:   @@snip [FlatMapMerge.scala](/akka-docs/src/test/scala/docs/stream/operators/sourceorflow/FlatMapMerge.scala) { #flatmap-merge }

Java
:   @@snip [FlatMapMerge.java](/akka-docs/src/test/java/jdocs/stream/operators/sourceorflow/FlatMapMerge.java) { #flatmap-merge }


## Reactive Streams semantics

@@@div { .callout }

**emits** when one of the currently consumed substreams has an element available

**backpressures** when downstream backpressures or the max number of substreams is reached

**completes** when upstream completes and all consumed substreams complete

@@@


