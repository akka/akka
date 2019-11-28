# groupedWithin

Chunk up this stream into groups of elements received within a time window, or limited by the number of the elements, whatever happens first.

@ref[Timer driven operators](../index.md#timer-driven-operators)

@@@div { .group-scala }

## Signature

@@signature [Flow.scala](/akka-stream/src/main/scala/akka/stream/scaladsl/Flow.scala) { #groupedWithin }

@@@

## Description

Chunk up this stream into groups of elements received within a time window, or limited by the number of the elements,
whatever happens first. Empty groups will not be emitted if no elements are received from upstream.
The last group before end-of-stream will contain the buffered elements since the previously emitted group.

## Reactive Streams semantics

@@@div { .callout }

**emits** when the configured time elapses since the last group has been emitted,
but not if no elements has been grouped (i.e: no empty groups), or when limit has been reached.

**backpressures** downstream backpressures, and there are *n+1* buffered elements

**completes** when upstream completes

@@@

