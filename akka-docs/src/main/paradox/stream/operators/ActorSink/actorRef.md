# ActorSink.actorRef

Sends the elements of the stream to the given @java[`ActorRef<T>`]@scala[`ActorRef[T]`], without considering backpressure.

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

@@signature [ActorSink.scala](/akka-stream-typed/src/main/scala/akka/stream/typed/scaladsl/ActorSink.scala) { #actorRef }

@@@

## Description

Sends the elements of the stream to the given `ActorRef`.
If the target actor terminates the stream will be canceled.
When the stream is completed successfully the given `onCompleteMessage`
will be sent to the destination actor.
When the stream is completed with failure a the throwable that was signaled
to the stream is adapted to the Actors protocol using `onFailureMessage` and
then then sent to the destination actor.

It will request at most `maxInputBufferSize` number of elements from
upstream, but there is no back-pressure signal from the destination actor,
i.e. if the actor is not consuming the messages fast enough the mailbox
of the actor will grow. For potentially slow consumer actors it is recommended
to use a bounded mailbox with zero `mailbox-push-timeout-time` or use a rate
limiting operator in front of this `Sink`.

## Examples

TODO (in progress)
