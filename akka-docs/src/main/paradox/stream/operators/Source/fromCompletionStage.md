# Source.fromCompletionStage

Deprecated by @ref:[`Source.completionStage`](completionStage.md).

@ref[Source operators](../index.md#source-operators)

## Signature

@apidoc[Source.fromCompletionStage](Source$) { scala="#fromCompletionStage[T](future:java.util.concurrent.CompletionStage[T]):akka.stream.scaladsl.Source[T,akka.NotUsed]" java="#fromCompletionStage(java.util.concurrent.CompletionStage)" }


## Description

`fromCompletionStage` has been deprecated in 2.6.0, use @ref:[completionStage](completionStage.md) instead.

Send the single value of the `CompletionStage` when it completes and there is demand.
If the `CompletionStage` completes with `null` stage is completed without emitting a value.
If the `CompletionStage` fails the stream is failed with that exception.

## Reactive Streams semantics

@@@div { .callout }

**emits** the future completes

**completes** after the future has completed

@@@

