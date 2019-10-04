# Source.unfoldAsync

Just like `unfold` but the fold function returns a @scala[`Future`] @java[`CompletionStage`].

@ref[Source operators](../index.md#source-operators)

@@@div { .group-scala }

## Signature

@@signature [Source.scala](/akka-stream/src/main/scala/akka/stream/scaladsl/Source.scala) { #unfoldAsync }

@@@

## Description

Just like `unfold` but the fold function returns a @scala[`Future`] @java[`CompletionStage`] which will cause the source to
complete or emit when it completes.

Can be used to implement many stateful sources without having to touch the more low level @ref[`GraphStage`](../../stream-customize.md) API.

## Reactive Streams semantics

@@@div { .callout }

**emits** when there is demand and unfold state returned future completes with some value

**completes** when the @scala[future] @java[CompletionStage] returned by the unfold function completes with an empty value

@@@

