# RetryFlow.withBackoffAndContext

Wrap the given @apidoc[FlowWithContext] and retry individual elements in the stream with an exponential backoff. A decider function tests every element and can return a new element to be sent to the wrapped flow for another try.

@ref[Error handling](../index.md#error-handling)

@@@div { .group-scala }

## Signature

@@signature [RetryFlow.scala](/akka-stream/src/main/scala/akka/stream/scaladsl/RetryFlow.scala) { #withBackoffAndContext }

@@@

## Description

When an element is emitted by the wrapped `flow` it is passed to the `decideRetry` function, which may return an element to retry in the `flow`. 

The retry backoff is controlled by the `minBackoff`, `maxBackoff` and `randomFactor` parameters.
At most `maxRetries` will be made after the initial try.

The wrapped `flow` must have **one-in one-out semantics**. It may not filter, nor duplicate elements.

Elements are retried as long as `maxRetries` is not reached and the `decideRetry` function returns a new element to be sent to `flow`. The `decideRetry` function gets passed in the original element sent to the `flow` and the element emitted by it together with their contexts as @scala[tuples]@java[`akka.japi.Pair`s].
When `decideRetry` returns @scala[`None`]@java[`Optional.empty`], no retries will be issued, and the response will be emitted downstream.

This example wraps a `flow` which emits elements of Scala's `Try` type which may contain an exception to indicate failure. When it hits a failure the value is incremented and sent for retrying.

Scala
:   @@snip [RetryFlowSpec.scala](/akka-stream-tests/src/test/scala/akka/stream/scaladsl/RetryFlowSpec.scala) { #retry-failure }

Java
:   @@snip [RetryFlowTest.java](/akka-stream-tests/src/test/java/akka/stream/javadsl/RetryFlowTest.java) { #retry-failure }

Even when the `flow` emits a successful `Try`, `decideRetry` may request a retry. 

Scala
:   @@snip [RetryFlowSpec.scala](/akka-stream-tests/src/test/scala/akka/stream/scaladsl/RetryFlowSpec.scala) { #retry-success }

Java
:   @@snip [RetryFlowTest.java](/akka-stream-tests/src/test/java/akka/stream/javadsl/RetryFlowTest.java) { #retry-success }

## Reactive Streams semantics

@@@div { .callout }

**emits** when the wrapped flow emits, and either `maxRetries` is reached or `decideRetry` returns @scala[`None`]@java[`Optional.empty`]

**backpressures** during backoff, when the wrapped flow backpressures, or when downstream backpressures

**completes** when upstream or the wrapped flow completes

@@@
