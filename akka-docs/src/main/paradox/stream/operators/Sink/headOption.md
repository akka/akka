# Sink.headOption

Materializes into a @scala[`Future[Option[T]]`] @java[`CompletionStage<Optional<T>>`] which completes with the first value arriving wrapped in @scala[`Some`] @java[`Optional`], or @scala[a `None`] @java[an empty Optional] if the stream completes without any elements emitted.

@ref[Sink operators](../index.md#sink-operators)

## Signature

@apidoc[Sink.headOption](Sink$) { scala="#headOption[T]:akka.stream.scaladsl.Sink[T,scala.concurrent.Future[Option[T]]]" java="#headOption()" }


## Description

Materializes into a @scala[`Future[Option[T]]`] @java[`CompletionStage<Optional<T>>`] which completes with the first value arriving wrapped in @scala[`Some`] @java[`Optional`],
or @scala[a `None`] @java[an empty Optional] if the stream completes without any elements emitted.

## Example

In this example there is an empty source i.e. it does not emit any element and to handle it we have used headOption operator which will complete with None.

Scala
:   @@snip [HeadOption.scala](/akka-docs/src/test/scala/docs/stream/operators/sink/HeadOption.scala) { #headoption }

Java
:   @@snip [SinkDocExamples.java](/akka-docs/src/test/java/jdocs/stream/operators/SinkDocExamples.java) { #headoption }

## Reactive Streams semantics

@@@div { .callout }

**cancels** after receiving one element

**backpressures** never

@@@


