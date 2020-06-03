# ActorSink.actorRefWithBackpressure

Sends the elements of the stream to the given @java[`ActorRef<T>`]@scala[`ActorRef[T]`] of the new actors API with backpressure, to be able to signal demand when the actor is ready to receive more elements.

@ref[Actor interop operators](../index.md#actor-interop-operators)

## Dependency

This operator is included in:

@@dependency[sbt,Maven,Gradle] {
  symbol1=AkkaVersion
  value1="$akka.version$"
  group="com.typesafe.akka"
  artifact="akka-stream-typed_$scala.binary.version$"
  version=AkkaVersion
}

## Signature

@apidoc[ActorSink.actorRefWithBackpressure](ActorSink$) { scala="#actorRefWithBackpressure[T,M,A](ref:akka.actor.typed.ActorRef[M],messageAdapter:(akka.actor.typed.ActorRef[A],T)=&gt;M,onInitMessage:akka.actor.typed.ActorRef[A]=&gt;M,ackMessage:A,onCompleteMessage:M,onFailureMessage:Throwable=&gt;M):akka.stream.scaladsl.Sink[T,akka.NotUsed]" java="#actorRefWithBackpressure(akka.actor.typed.ActorRef,akka.japi.function.Function2,akka.japi.function.Function,java.lang.Object,java.lang.Object,akka.japi.function.Function)" }

## Description

Sends the elements of the stream to the given @java[`ActorRef<T>`]@scala[`ActorRef[T]`] with backpressure, to be able to signal demand when the actor is ready to receive more elements.

This operator needs to be set up with the protocol to the receiving actor:

1. `ref`: the receiving actor as @java[`ActorRef<T>`]@scala[`ActorRef[T]`] (where `T` must include the control messages below)
1. `messageAdapter`: a function that wraps the stream elements to be sent to the actor together with an @java[`ActorRef<ACK>`]@scala[`ActorRef[ACK]`] which accepts the ack message
1. `onInitMessage`: a function that wraps an @java[`ActorRef<ACK>`]@scala[`ActorRef[ACK]`] into a messages to couple the receiving actor to this sink
1. `ackMessage`: a fixed message that is expected after every element sent to the receiving actor
1. `onCompleteMessage`: the fixed message to be sent to the actor when the stream completes
1. `onFailureMessage`: a function that creates a message from a `Throwable` to be sent to the actor in case the stream fails

See also:

* @ref[`ActorSink.actorRef`](../ActorSink/actorRefWithBackpressure.md) Send elements to an actor of the new actors API, without considering backpressure
* @ref[`Sink.actorRef`](../Sink/actorRef.md) Send elements to an actor of the classic actors API, without considering backpressure
* @ref[`Sink.actorRefWithBackpressue`](../Sink/actorRefWithBackpressure.md) The corresponding operator for the classic actors API

## Examples

Scala
:  @@snip [ActorSourceSinkExample.scala](/akka-stream-typed/src/test/scala/docs/akka/stream/typed/ActorSourceSinkExample.scala) { #actor-sink-ref-with-backpressure }

Java
:  @@snip [ActorSinkWithAckExample.java](/akka-stream-typed/src/test/java/docs/akka/stream/typed/ActorSinkWithAckExample.java) { #actor-sink-ref-with-backpressure }

## Reactive Streams semantics

@@@div { .callout }

**cancels** when the actor terminates

**backpressures** when the actor acknowledgement has not arrived

@@@

