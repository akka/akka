# Sink.headOption

Materializes into a @scala[`Future[Option[T]]`] @java[`CompletionStage<Optional<T>>`] which completes with the first value arriving wrapped in @scala[`Some`] @java[`Optional`], or @scala[a `None`] @java[an empty Optional] if the stream completes without any elements emitted.

@ref[Sink stages](../index.md#sink-stages)

@@@div { .group-scala }

## Signature

@@signature [Sink.scala]($akka$/akka-stream/src/main/scala/akka/stream/scaladsl/Sink.scala) { #headOption }

@@@

## Description

Materializes into a @scala[`Future[Option[T]]`] @java[`CompletionStage<Optional<T>>`] which completes with the first value arriving wrapped in @scala[`Some`] @java[`Optional`],
or @scala[a `None`] @java[an empty Optional] if the stream completes without any elements emitted.


@@@div { .callout }

**cancels** after receiving one element

**backpressures** never

@@@


