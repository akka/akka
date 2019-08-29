# RetryFlow.withBackoffAndContext

Wrap the given @apidoc[FlowWithContext] with a @apidoc[FlowWithContext] that will retry individual elements in the stream with an exponential backoff.

@ref[Error handling](../index.md#error-handling)

@@@div { .group-scala }

## Signature

@@signature [RetryFlow.scala](/akka-stream/src/main/scala/akka/stream/scaladsl/RetryFlow.scala) { #withBackoffAndContext }

@@@

## Description

This is equivalent to @ref[RetryFlow.withBackoff](withBackoff.md) but uses @apidoc[FlowWithContext] instead.

## Reactive Streams semantics

@@@div { .callout }

**emits** when the wrapped flow emits and the retry decision function decides not to retry or emit failure

**backpressures** during backoff and when the wrapped flow backpressures

**completes** when the wrapped flow completes

@@@
