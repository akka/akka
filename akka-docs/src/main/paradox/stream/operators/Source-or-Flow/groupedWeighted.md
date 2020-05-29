# groupedWeighted

Accumulate incoming events until the combined weight of elements is greater than or equal to the minimum weight and then pass the collection of elements downstream.

@ref[Simple operators](../index.md#simple-operators)

## Signature

@apidoc[Source.groupedWeighted](Source) { scala="#groupedWeighted(minWeight:Long)(costFn:Out=&gt;Long):FlowOps.this.Repr[scala.collection.immutable.Seq[Out]]" java="#groupedWeighted(long,akka.japi.function.Function)" }
@apidoc[Flow.groupedWeighted](Flow) { scala="#groupedWeighted(minWeight:Long)(costFn:Out=&gt;Long):FlowOps.this.Repr[scala.collection.immutable.Seq[Out]]" java="#groupedWeighted(long,akka.japi.function.Function)" }


## Description

Chunk up this stream into groups of elements that have a cumulative weight greater than or equal to the `minWeight`, with the last group possibly smaller than requested `minWeight` due to end-of-stream.

See also:

* @ref[grouped](grouped.md) for a variant that groups based on number of elements
* @ref[groupedWithin](groupedWithin.md) for a variant that groups based on number of elements and a time window
* @ref[groupedWeightedWithin](groupedWeightedWithin.md) for a variant that groups based on element weight and a time window

## Examples

The below example demonstrates how `groupedWeighted` groups the accumulated elements into @scala[`Seq`] @java[`List`]
and maps with other operation.

Scala
:  @@snip [groupedWeighted.scala](/akka-docs/src/test/scala/docs/stream/operators/sourceorflow/GroupedWeighted.scala) { #groupedWeighted }

Java
:  @@snip [SourceOrFlow.java](/akka-docs/src/test/java/jdocs/stream/operators/SourceOrFlow.java) { #groupedWeighted }

## Reactive Streams semantics

@@@div { .callout }

**emits** when the cumulative weight of elements is greater than or equal to the minimum weight or upstream completed

**backpressures** when a group has been assembled and downstream backpressures

**completes** when upstream completes

@@@


