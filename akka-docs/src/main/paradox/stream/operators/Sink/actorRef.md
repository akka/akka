# actorRef

Send the elements from the stream to an `ActorRef`.

@ref[Sink operators](../index.md#sink-operators)

@@@ div { .group-scala }
## Signature

@@signature [Sink.scala](/akka-stream/src/main/scala/akka/stream/scaladsl/Sink.scala) { #actorRef }
@@@

## Description

Send the elements from the stream to an `ActorRef`. No backpressure so care must be taken to not overflow the inbox.

## Reactive Streams semantics

@@@div { .callout }

**cancels** when the actor terminates

**backpressures** never

@@@


