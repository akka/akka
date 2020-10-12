# MergeSequence

Merge a linear sequence partitioned across multiple sources.

@ref[Fan-in operators](index.md#fan-in-operators)

## Signature

@apidoc[MergeSequence]

## Description

Merge a linear sequence partitioned across multiple sources. Each element from upstream must have a defined index,
starting from 0. There must be no gaps in the sequence, nor may there be any duplicates. Each upstream source must be
ordered by the sequence.

## Example

`MergeSequence` is most useful when used in combination with `Partition`, to merge the partitioned stream back into
a single stream, while maintaining the order of the original elements. `zipWithIndex` can be used before partitioning
the stream to generate the index.

The example below shows partitioning a stream of messages into one stream for elements that must be processed by a
given processing flow, and another stream for elements for which no processing will be done, and then merges them
back together so that the messages can be acknowledged in order.

Scala
:   @@snip [MergeSequenceDocExample.scala](/akka-docs/src/test/scala/docs/stream/operators/MergeSequenceDocExample.scala) { #merge-sequence }

Java
:   @@snip [MergeSequenceDocExample.java](/akka-docs/src/test/java/jdocs/stream/operators/MergeSequenceDocExample.java) { #import #merge-sequence }

## Reactive Streams semantics

@@@div { .callout }

**emits** when one of the upstreams has the next expected element in the sequence available.

**backpressures** when downstream backpressures

**completes** when all upstreams complete

**cancels** downstream cancels

@@@

