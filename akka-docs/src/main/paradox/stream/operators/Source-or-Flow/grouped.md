# grouped

Accumulate incoming events until the specified number of elements have been accumulated and then pass the collection of elements downstream.

@ref[Simple operators](../index.md#simple-operators)

## Signature

@apidoc[Source.grouped](Source) { scala="#grouped(n:Int):FlowOps.this.Repr[scala.collection.immutable.Seq[Out]]" java="#grouped(int)" }
@apidoc[Flow.grouped](Flow) { scala="#grouped(n:Int):FlowOps.this.Repr[scala.collection.immutable.Seq[Out]]" java="#grouped(int)" }


## Description

Accumulate incoming events until the specified number of elements have been accumulated and then pass the collection of
elements downstream.

See also:

* @ref[groupedWeighted](groupedWeighted.md) for a variant that groups based on element weight
* @ref[groupedWithin](groupedWithin.md) for a variant that groups based on number of elements and a time window
* @ref[groupedWeightedWithin](groupedWeightedWithin.md) for a variant that groups based on element weight and a time window

## Examples

The below example demonstrates how `grouped` groups the accumulated elements into @scala[`Seq`] @java[`List`]
and maps with other operation.

Scala
:  @@snip [Grouped.scala](/akka-docs/src/test/scala/docs/stream/operators/sourceorflow/Grouped.scala) { #grouped }

Java
:  @@snip [SourceOrFlow.java](/akka-docs/src/test/java/jdocs/stream/operators/SourceOrFlow.java) { #grouped }

## Reactive Streams semantics

@@@div { .callout }

**emits** when the specified number of elements has been accumulated or upstream completed

**backpressures** when a group has been assembled and downstream backpressures

**completes** when upstream completes

@@@


