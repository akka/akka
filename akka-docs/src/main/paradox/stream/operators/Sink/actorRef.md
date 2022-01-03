# Sink.actorRef

Send the elements from the stream to an `ActorRef` of the classic actors API.

@ref[Actor interop operators](../index.md#actor-interop-operators)

## Signature

@apidoc[Sink.actorRef](Sink$) { scala="#actorRef[T](ref:akka.actor.ActorRef,onCompleteMessage:Any,onFailureMessage:Throwable=&gt;Any):akka.stream.scaladsl.Sink[T,akka.NotUsed]" java="#actorRef(akka.actor.ActorRef,java.lang.Object)" }

## Description

Send the elements from the stream to an `ActorRef`. No backpressure so care must be taken to not overflow the inbox.

See also:

* @ref[`Sink.actorRefWithBackpressue`](../Sink/actorRefWithBackpressure.md) Send elements to an actor with backpressure support
* @ref[`ActorSink.actorRef`](../ActorSink/actorRef.md) The corresponding operator for the new actors API
* @ref[`ActorSink.actorRefWithBackpressure`](../ActorSink/actorRefWithBackpressure.md) Send elements to an actor of the new actors API supporting backpressure

## Reactive Streams semantics

@@@div { .callout }

**cancels** when the actor terminates

**backpressures** never

@@@


