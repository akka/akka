# ActorSource.actorRefWithBackpressure

Materialize an @java[`ActorRef<T>`]@scala[`ActorRef[T]`]; sending messages to it will emit them on the stream. The source acknowledges reception after emitting a message, to provide back pressure from the source.

@ref[Actor interop operators](../index.md#actor-interop-operators)

## Dependency

This operator is included in:

@@dependency[sbt,Maven,Gradle] {
  group="com.typesafe.akka"
  artifact="akka-stream-typed_$scala.binary.version$"
  version="$akka.version$"
}

## Signature

@apidoc[ActorSource.actorRefWithBackpressure](ActorSource$) { scala="#actorRefWithBackpressure[T,Ack](ackTo:akka.actor.typed.ActorRef[Ack],ackMessage:Ack,completionMatcher:PartialFunction[T,akka.stream.CompletionStrategy],failureMatcher:PartialFunction[T,Throwable]):akka.stream.scaladsl.Source[T,akka.actor.typed.ActorRef[T]]" java="#actorRefWithBackpressure(akka.actor.typed.ActorRef,java.lang.Object,akka.japi.function.Function,akka.japi.function.Function)" }

## Description

Materialize an @java[`ActorRef<T>`]@scala[`ActorRef[T]`], sending messages to it will emit them on the stream. The actor responds with the provided ack message
once the element could be emitted allowing for backpressure from the source. Sending another message before the previous one has been acknowledged will fail the stream.

## Reactive Streams semantics

@@@div { .callout }

**emits** when there is demand and there are messages in the buffer or a message is sent to the `ActorRef`

**completes** when the `ActorRef` is sent `akka.actor.Status.Success`

@@@
