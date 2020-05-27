# groupedWeightedWithin

Chunk up this stream into groups of elements received within a time window, or limited by the weight of the elements, whatever happens first.

@ref[Timer driven operators](../index.md#timer-driven-operators)

## Signature

@apidoc[Source.groupedWeightedWithin](Source) { scala="#groupedWeightedWithin(maxWeight:Long,d:scala.concurrent.duration.FiniteDuration)(costFn:Out=&gt;Long):FlowOps.this.Repr[scala.collection.immutable.Seq[Out]]" java="#groupedWeightedWithin(long,akka.japi.function.Function,java.time.Duration)" }
@apidoc[Flow.groupedWeightedWithin](Flow) { scala="#groupedWeightedWithin(maxWeight:Long,d:scala.concurrent.duration.FiniteDuration)(costFn:Out=&gt;Long):FlowOps.this.Repr[scala.collection.immutable.Seq[Out]]" java="#groupedWeightedWithin(long,akka.japi.function.Function,java.time.Duration)" }


## Description

Chunk up this stream into groups of elements received within a time window, or limited by the weight of the elements,
whatever happens first. Empty groups will not be emitted if no elements are received from upstream.
The last group before end-of-stream will contain the buffered elements since the previously emitted group.

## Reactive Streams semantics

@@@div { .callout }

**emits** when the configured time elapses since the last group has been emitted,
but not if no elements has been grouped (i.e: no empty groups), or when weight limit has been reached.

**backpressures** downstream backpressures, and buffered group (+ pending element) weighs more than *maxWeight*

**completes** when upstream completes

@@@

