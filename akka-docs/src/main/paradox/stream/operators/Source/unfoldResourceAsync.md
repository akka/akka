# Source.unfoldResourceAsync

Wrap any resource that can be opened, queried for next element (in a blocking way) and closed using three distinct functions into a source.

@ref[Source operators](../index.md#source-operators)

@@@div { .group-scala }

## Signature

@@signature [Source.scala](/akka-stream/src/main/scala/akka/stream/scaladsl/Source.scala) { #unfoldResourceAsync }

@@@

## Description

Wrap any resource that can be opened, queried for next element (in a blocking way) and closed using three distinct functions into a source.
Functions return @scala[`Future`] @java[`CompletionStage`] to achieve asynchronous processing

## Reactive Streams semantics

@@@div { .callout }

**emits** when there is demand and @scala[`Future`] @java[`CompletionStage`] from read function returns value

**completes** when @scala[`Future`] @java[`CompletionStage`] from read function returns `None`

@@@

