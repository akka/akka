# Sink.lastOption

Materialize a @scala[`Future[Option[T]]`] @java[`CompletionStage<Optional<T>>`] which completes with the last value emitted wrapped in an @scala[`Some`] @java[`Optional`] when the stream completes.

@ref[Sink operators](../index.md#sink-operators)

@@@div { .group-scala }

## Signature

@@signature [Sink.scala](/akka-stream/src/main/scala/akka/stream/scaladsl/Sink.scala) { #lastOption }

@@@

## Description

Materialize a @scala[`Future[Option[T]]`] @java[`CompletionStage<Optional<T>>`] which completes with the last value
emitted wrapped in an @scala[`Some`] @java[`Optional`] when the stream completes. if the stream completes with no elements the `CompletionStage` is
completed with @scala[`None`] @java[an empty `Optional`].

## Reactive Streams semantics

@@@div { .callout }

**cancels** never

**backpressures** never

@@@

## Example

Scala
:   @@snip [LastSinkSpec.scala](/akka-stream-tests/src/test/scala/akka/stream/scaladsl/LastSinkSpec.scala) { #lastOption-operator-example }

Java
:   @@snip [SinkDocExamples.java](/akka-docs/src/test/java/jdocs/stream/operators/SinkDocExamples.java) { #lastOption-operator-example }