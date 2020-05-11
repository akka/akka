# Source.future

Send the single value of the `Future` when it completes and there is demand.

@ref[Source operators](../index.md#source-operators)

## Signature

@apidoc[Source.future](Source$) { scala="#future[T](futureElement:scala.concurrent.Future[T]):akka.stream.scaladsl.Source[T,akka.NotUsed]" }

## Description

Send the single value of the `Future` when it completes and there is demand.
If the future fails the stream is failed with that exception.

For the corresponding operator for the Java standard library `CompletionStage` see @ref:[completionStage](completionStage.md).

## Example

Scala
:  @@snip [SourceFromFuture.scala](/akka-docs/src/test/scala/docs/stream/operators/SourceOperators.scala) { #sourceFromFuture }

## Reactive Streams semantics

@@@div { .callout }

**emits** the future completes

**completes** after the future has completed

@@@
