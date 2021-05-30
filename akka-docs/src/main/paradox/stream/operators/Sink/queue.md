# Sink.queue

Materialize a `SinkQueue` that can be pulled to trigger demand through the sink.

@ref[Sink operators](../index.md#sink-operators)

## Signature

@apidoc[Sink.queue](Sink$) { scala="#queue[T](maxConcurrentPulls:Int):akka.stream.scaladsl.Sink[T,akka.stream.scaladsl.SinkQueueWithCancel[T]]" java="#queue(int)" }


## Description

Materialize a `SinkQueue` that can be pulled to trigger demand through the sink. The queue contains
a buffer in case stream emitting elements faster than queue pulling them.


## Reactive Streams semantics

@@@div { .callout }

**cancels** when  `SinkQueue.cancel` is called

**backpressures** when buffer has some space

@@@

