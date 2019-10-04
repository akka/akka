# ActorSource.actorRefWithBackpressure

Materialize an @java[`ActorRef<T>`]@scala[`ActorRef[T]`]; sending messages to it will emit them on the stream. The source acknowledges reception after emitting a message, to provide back pressure from the source.

@ref[Actor interop operators](../index.md#actor-interop-operators)

## Dependency

This operator is included in:

@@dependency[sbt,Maven,Gradle] {
  group="com.typesafe.akka"
  artifact="akka-stream-typed_$scala.binary_version$"
  version="$akka.version$"
}

@@@div { .group-scala }

## Signature

@@signature [ActorSource.scala](/akka-stream-typed/src/main/scala/akka/stream/typed/scaladsl/ActorSource.scala) { #actorRefWithBackpressure }
@@@

## Description

Materialize an @java[`ActorRef<T>`]@scala[`ActorRef[T]`], sending messages to it will emit them on the stream. The actor responds with the provided ack message
once the element could be emitted allowing for backpressure from the source. Sending another message before the previous one has been acknowledged will fail the stream.

## Reactive Streams semantics

@@@div { .callout }

**emits** when there is demand and there are messages in the buffer or a message is sent to the `ActorRef`

**completes** when the `ActorRef` is sent `akka.actor.Status.Success`

@@@
