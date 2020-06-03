# Sink.actorRef

Send the elements from the stream to an `ActorRef`.

@ref[Sink operators](../index.md#sink-operators)

## Signature

@apidoc[Sink.actorRef](Sink$) { scala="#actorRef[T](ref:akka.actor.ActorRef,onCompleteMessage:Any,onFailureMessage:Throwable=&gt;Any):akka.stream.scaladsl.Sink[T,akka.NotUsed]" java="#actorRef(akka.actor.ActorRef,java.lang.Object)" }

## Description

Send the elements from the stream to an `ActorRef`. No backpressure so care must be taken to not overflow the inbox.

## Reactive Streams semantics

@@@div { .callout }

**cancels** when the actor terminates

**backpressures** never

@@@


