# Sink.queue

Materialize a `SinkQueue` that can be pulled to trigger demand through the sink.

@ref[Sink operators](../index.md#sink-operators)

@@@div { .group-scala }

## Signature

@@signature [Sink.scala](/akka-stream/src/main/scala/akka/stream/scaladsl/Sink.scala) { #queue }

@@@

## Description

Materialize a `SinkQueue` that can be pulled to trigger demand through the sink. The queue contains
a buffer in case stream emitting elements faster than queue pulling them.


## Reactive Streams semantics

@@@div { .callout }

**cancels** when  `SinkQueue.cancel` is called

**backpressures** when buffer has some space

@@@

