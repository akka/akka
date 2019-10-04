# Sink.onComplete

Invoke a callback when the stream has completed or failed.

@ref[Sink operators](../index.md#sink-operators)

@@@div { .group-scala }

## Signature

@@signature [Sink.scala](/akka-stream/src/main/scala/akka/stream/scaladsl/Sink.scala) { #onComplete }

@@@

## Description

Invoke a callback when the stream has completed or failed.

## Reactive Streams semantics

@@@div { .callout }

**cancels** never

**backpressures** never

@@@


