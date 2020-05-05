# Sink.collection

@scala[Collect all values emitted from the stream into a collection.]@java[Operator only available in the Scala API. The closest operator in the Java API is @ref[`Sink.seq`](seq.md)].

@ref[Sink operators](../index.md#sink-operators)

## Signature

@apidoc[Sink.collection](Sink$) { scala="#collection[T,That](implicitcbf:akka.util.ccompat.Factory[T,Thatwithscala.collection.immutable.Iterable[_]]):akka.stream.scaladsl.Sink[T,scala.concurrent.Future[That]]" }


## Description

@scala[Collect values emitted from the stream into an arbitrary collection `That`. The resulting collection is available through a `Future[That]` or when the stream completes. Note that the collection boundaries are those defined in the `CanBuildFrom` associated with the chosen collection. See [The Architecture of Scala 2.13's Collections](https://docs.scala-lang.org/overviews/core/architecture-of-scala-213-collections.html) for more info. The [`seq`](seq.html) operator is a shorthand for `Sink.collection[T, Vector[T]]`.]@java[Operator only available in the Scala API. The closest operator in the Java API is [`Sink.seq`](seq.html).]

## Reactive Streams semantics

@@@

@@@div { .group-scala .callout }

**cancels** If too many values are collected

@@@
