# RetryFlow.withBackoffAndContext

Wrap the given @apidoc[FlowWithContext] with a @apidoc[FlowWithContext] that will retry individual elements in the stream with an exponential backoff.

@ref[Error handling](../index.md#error-handling)

@@@div { .group-scala }

## Signature

@@signature [RetryFlow.scala](/akka-stream/src/main/scala/akka/stream/scaladsl/RetryFlow.scala) { #withBackoffAndContext }

@@@

## Description

When an element is emitted by the wrapped `flow` it is passed to the `decideRetry` function, which decides whether
a retry should be issued. The retry backoff is controlled by the `minBackoff`, `maxBackoff` and `randomFactor` parameters.

The wrapped `flow` must adhere to "one-in one-out semantics" as required for any `FlowWithContext`.

Elements can be retried by returning a new request from `decideRetry` as long as `maxRetries` is not reached.

Scala
:   @@snip [RetryFlowSpec.scala](/akka-stream-tests/src/test/scala/akka/stream/scaladsl/RetryFlowSpec.scala) { #retry-failure }

Java
:   @@snip [RetryFlowTest.java](/akka-stream-tests/src/test/java/akka/stream/javadsl/RetryFlowTest.java) { #retry-failure }

The `decideRetry` is passed the original element with its context and the element emitted by `flow` with its context.

When `decideRetry` returns @scala[`Option.none`]@java[`Optional.empty`],
no retries will be issued, and the response will be emitted downstream.


Scala
:   @@snip [RetryFlowSpec.scala](/akka-stream-tests/src/test/scala/akka/stream/scaladsl/RetryFlowSpec.scala) { #retry-success }

Java
:   @@snip [RetryFlowTest.java](/akka-stream-tests/src/test/java/akka/stream/javadsl/RetryFlowTest.java) { #retry-success }

## Reactive Streams semantics

@@@div { .callout }

**emits** when the wrapped flow emits and the retry decision function decides not to retry or emit failure

**backpressures** during backoff and when the wrapped flow backpressures

**completes** when the wrapped flow completes

@@@
