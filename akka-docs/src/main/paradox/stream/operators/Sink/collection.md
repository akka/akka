# @scala[Sink.collection]@java[Operator only available in the Scala API. In Java, use [`Seq`](seq.html)]

Collect all values emitted from the stream into a collection.

@ref[Sink operators](../index.md#sink-operators)

@@@div { .group-scala }

## Signature

@@signature [Sink.scala](/akka-stream/src/main/scala/akka/stream/scaladsl/Sink.scala) { #collection }

@@@

## Description

Collect values emitted from the stream into an arbitrary collection. The resulting collection is available
through a `Future` or when the stream completes. Note that the collection boundaries are those defined in the
`CanBuildFrom` associated with the chosen collection. 
See [The Architecture of Scala 2.13's Collections](https://docs.scala-lang.org/overviews/core/architecture-of-scala-213-collections.html) for more info.

## Reactive Streams semantics

@@@div { .callout }

**cancels** If too many values are collected

@@@

