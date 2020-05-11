# Sink.futureSink

Streams the elements to the given future sink once it successfully completes. 

@ref[Sink operators](../index.md#sink-operators)

## Signature

@apidoc[Sink.futureSink](Sink$) { scala="#futureSink[T,M](future:scala.concurrent.Future[akka.stream.scaladsl.Sink[T,M]]):akka.stream.scaladsl.Sink[T,scala.concurrent.Future[M]]" }


## Description

Streams the elements through the given future flow once it successfully completes. 
If the future fails the stream is failed.

## Reactive Streams semantics

@@@div { .callout }

**cancels** if the future fails or if the created sink cancels 

**backpressures** when initialized and when created sink backpressures

@@@


