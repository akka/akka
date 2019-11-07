# Sink.futureSink

Streams the elements to the given future sink once it successfully completes. 

@ref[Sink operators](../index.md#sink-operators)

@@@div { .group-scala }

## Signature

@@signature [Sink.scala](/akka-stream/src/main/scala/akka/stream/scaladsl/Sink.scala) { #futureSink }

@@@

## Description

Streams the elements through the given future flow once it successfully completes. 
If the future fails the stream is failed.

## Reactive Streams semantics

@@@div { .callout }

**cancels** if the future fails or if the created sink cancels 

**backpressures** when initialized and when created sink backpressures

@@@


