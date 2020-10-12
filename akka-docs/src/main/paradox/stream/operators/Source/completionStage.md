# Source.completionStage

Send the single value of the `CompletionStage` when it completes and there is demand.

@ref[Source operators](../index.md#source-operators)

## Signature

@apidoc[Source.completionStage](Source$) { scala="#completionStage[T](completionStage:java.util.concurrent.CompletionStage[T]):akka.stream.scaladsl.Source[T,akka.NotUsed]" java="#completionStage(java.util.concurrent.CompletionStage)" }


## Description

Send the single value of the `CompletionStage` when it completes and there is demand.
If the `CompletionStage` completes with `null` stage is completed without emitting a value.
If the `CompletionStage` fails the stream is failed with that exception.

For the corresponding operator for the Scala standard library `Future` see @ref:[future](future.md).

## Example

Java
:  @@snip [SourceFromCompletionStage.java](/akka-docs/src/test/java/jdocs/stream/operators/source/FromCompletionStage.java) { #sourceFromCompletionStage }

## Reactive Streams semantics

@@@div { .callout }

**emits** the future completes

**completes** after the future has completed

@@@
