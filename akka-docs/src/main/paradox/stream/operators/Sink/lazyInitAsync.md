# Sink.lazyInitAsync

Deprecated by @ref:[`Sink.lazyFutureSink`](lazyFutureSink.md).

@ref[Sink operators](../index.md#sink-operators)

## Signature

@apidoc[Flow.lazyInitAsync](Flow$) { scala="#lazyInitAsync[I,O,M](flowFactory:()=&gt;scala.concurrent.Future[akka.stream.scaladsl.Flow[I,O,M]]):akka.stream.scaladsl.Flow[I,O,scala.concurrent.Future[Option[M]]]" java="#lazyInitAsync(akka.japi.function.Creator)" }
@apidoc[Sink.lazyInitAsync](Sink$) { scala="#lazyInitAsync[T,M](sinkFactory:()=&gt;scala.concurrent.Future[akka.stream.scaladsl.Sink[T,M]]):akka.stream.scaladsl.Sink[T,scala.concurrent.Future[Option[M]]]" java="#lazyInitAsync(akka.japi.function.Creator)" }


## Description

`lazyInitAsync` has been deprecated in 2.6.0, use @ref:[lazyFutureSink](lazyFutureSink.md) instead.

Creates a real `Sink` upon receiving the first element. Internal `Sink` will not be created if there are no elements,
because of completion or error.

- If upstream completes before an element was received then the @scala[`Future`]@java[`CompletionStage`] is completed with @scala[`None`]@java[an empty `Optional`].
- If upstream fails before an element was received, `sinkFactory` throws an exception, or materialization of the internal
  sink fails then the @scala[`Future`]@java[`CompletionStage`] is completed with the exception.
- Otherwise the @scala[`Future`]@java[`CompletionStage`] is completed with the materialized value of the internal sink.

## Reactive Streams semantics

@@@div { .callout }

**cancels** never

**backpressures** when initialized and when created sink backpressures

@@@


