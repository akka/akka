# Sink.headOption

Materializes into a @scala[`Future[Option[T]]`] @java[`CompletionStage<Optional<T>>`] which completes with the first value arriving wrapped in @scala[`Some`] @java[`Optional`], or @scala[a `None`] @java[an empty Optional] if the stream completes without any elements emitted.

@ref[Sink operators](../index.md#sink-operators)

## Signature

@apidoc[Sink.headOption](Sink$) { scala="#headOption[T]:akka.stream.scaladsl.Sink[T,scala.concurrent.Future[Option[T]]]" java="#headOption()" }


## Description

Materializes into a @scala[`Future[Option[T]]`] @java[`CompletionStage<Optional<T>>`] which completes with the first value arriving wrapped in @scala[`Some`] @java[`Optional`],
or @scala[a `None`] @java[an empty Optional] if the stream completes without any elements emitted.

## Reactive Streams semantics

@@@div { .callout }

**cancels** after receiving one element

**backpressures** never

@@@


