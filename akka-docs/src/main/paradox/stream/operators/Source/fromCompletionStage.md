# fromCompletionStage

Send the single value of the `CompletionStage` when it completes and there is demand.

@ref[Source operators](../index.md#source-operators)

@@@div { .group-scala }

## Signature

@@signature [Source.scala](/akka-stream/src/main/scala/akka/stream/scaladsl/Source.scala) { #fromCompletionStage }

@@@

## Description

Send the single value of the `CompletionStage` when it completes and there is demand.
If the `CompletionStage` completes with `null` stage is completed without emitting a value.
If the `CompletionStage` fails the stream is failed with that exception.


@@@div { .callout }

**emits** the future completes

**completes** after the future has completed

@@@

