# RetryFlow.withBackoff

Wrap the given @apidoc[Flow] with a @apidoc[Flow] that will retry individual elements in the stream with an exponential backoff.

@ref[Error handling](../index.md#error-handling)

@@@div { .group-scala }

## Signature

@@signature [RetryFlow.scala](/akka-stream/src/main/scala/akka/stream/scaladsl/RetryFlow.scala) { #withBackoff }

@@@

## Description

When an element is emitted by the wrapped `flow` it is passed to the `decideRetry` function, which decides whether
a retry should be issued as long as `maxRetries` in not reached. The retry backoff is controlled by the `minBackoff`, `maxBackoff` and `randomFactor` parameters.

Elements can be retried by returning a request from `decideRetry`:

Scala
:   @@snip [RetryFlowSpec.scala](/akka-stream-tests/src/test/scala/akka/stream/scaladsl/RetryFlowSpec.scala) { #retry-failure }

Java
:   @@snip [RetryFlowTest.java](/akka-stream-tests/src/test/java/akka/stream/javadsl/RetryFlowTest.java) { #retry-failure }

The `decideRetry` is passed the original element, the element emitted by `flow` and the pass-through value,
which can be used for different purposes, like:

* to correlate a request with a response
* to store request to be issued in the case of retry

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
