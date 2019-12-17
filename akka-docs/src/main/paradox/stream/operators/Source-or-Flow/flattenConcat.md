# flattenConcat

Flatten concat each input `Source`'s output elements into the output stream by concatenation,fully consuming one Source after the other, this method is equivalent to `flatMapConcat(identity)`.

@ref[Nesting and flattening operators](../index.md#nesting-and-flattening-operators)

@@@div { .group-scala }

## Signature

@@signature [Flow.scala](/akka-stream/src/main/scala/akka/stream/scaladsl/Flow.scala) { #flattenConcat }

@@@

## Description

Flatten concat each input `Source`'s output elements into the output stream by concatenation,
fully consuming one Source after the other, this method is equivalent to `flatMapConcat(identity)`.

## Reactive Streams semantics

@@@div { .callout }

**emits** when the current consumed substream has an element available

**backpressures** when downstream backpressures

**completes** when upstream completes and all consumed substreams complete

@@@

