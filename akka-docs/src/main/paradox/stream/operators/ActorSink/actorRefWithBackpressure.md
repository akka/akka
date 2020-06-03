# ActorSink.actorRefWithBackpressure

Sends the elements of the stream to the given @java[`ActorRef<T>`]@scala[`ActorRef[T]`] with backpressure, to be able to signal demand when the actor is ready to receive more elements.

@ref[Actor interop operators](../index.md#actor-interop-operators)

## Dependency

This operator is included in:

@@dependency[sbt,Maven,Gradle] {
  group="com.typesafe.akka"
  artifact="akka-stream-typed_$scala.binary.version$"
  version="$akka.version$"
}

## Signature

@apidoc[ActorSink.actorRefWithBackpressure](ActorSink$) { scala="#actorRefWithBackpressure[T,M,A](ref:akka.actor.typed.ActorRef[M],messageAdapter:(akka.actor.typed.ActorRef[A],T)=&gt;M,onInitMessage:akka.actor.typed.ActorRef[A]=&gt;M,ackMessage:A,onCompleteMessage:M,onFailureMessage:Throwable=&gt;M):akka.stream.scaladsl.Sink[T,akka.NotUsed]" java="#actorRefWithBackpressure(akka.actor.typed.ActorRef,akka.japi.function.Function2,akka.japi.function.Function,java.lang.Object,java.lang.Object,akka.japi.function.Function)" }

## Description

Sends the elements of the stream to the given @java[`ActorRef<T>`]@scala[`ActorRef[T]`] with backpressure, to be able to signal demand when the actor is ready to receive more elements.

## Examples

Scala
:  @@snip [ActorSourceSinkExample.scala](/akka-stream-typed/src/test/scala/docs/akka/stream/typed/ActorSourceSinkExample.scala) { #actor-sink-ref-with-backpressure }

Java
:  @@snip [ActorSinkWithAckExample.java](/akka-stream-typed/src/test/java/docs/akka/stream/typed/ActorSinkWithAckExample.java) { #actor-sink-ref-with-backpressure }
