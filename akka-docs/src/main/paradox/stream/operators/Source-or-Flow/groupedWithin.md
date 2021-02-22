# groupedWithin

Chunk up this stream into groups of elements received within a time window, or limited by the number of the elements, whatever happens first.

@ref[Timer driven operators](../index.md#timer-driven-operators)

## Signature

@apidoc[Source.groupedWithin](Source) { scala="#groupedWithin(n:Int,d:scala.concurrent.duration.FiniteDuration):FlowOps.this.Repr[scala.collection.immutable.Seq[Out]]" java="#groupedWithin(int,java.time.Duration)" }
@apidoc[Flow.groupedWithin](Flow) { scala="#groupedWithin(n:Int,d:scala.concurrent.duration.FiniteDuration):FlowOps.this.Repr[scala.collection.immutable.Seq[Out]]" java="#groupedWithin(int,java.time.Duration)" }


## Description

Chunk up this stream into groups of elements received within a time window, or limited by the number of the elements,
whatever happens first. Empty groups will not be emitted if no elements are received from upstream.
The last group before end-of-stream will contain the buffered elements since the previously emitted group.

See also:

* @ref[grouped](grouped.md) for a variant that groups based on number of elements
* @ref[groupedWeighted](groupedWeighted.md) for a variant that groups based on element weight
* @ref[groupedWeightedWithin](groupedWeightedWithin.md) for a variant that groups based on element weight and a time window

## Reactive Streams semantics

@@@div { .callout }

**emits** when the configured time elapses since the last group has been emitted,
but not if no elements has been grouped (i.e: no empty groups), or when limit has been reached.

**backpressures** downstream backpressures, and there are *n+1* buffered elements

**completes** when upstream completes

@@@

