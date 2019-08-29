# RetryFlow.withBackoff

Wrap the given @apidoc[Flow] with a @apidoc[Flow] that will retry individual elements in the stream with an exponential backoff.

@ref[Error handling](../index.md#error-handling)

@@@div { .group-scala }

## Signature

@@signature [RetryFlow.scala](/akka-stream/src/main/scala/akka/stream/scaladsl/RetryFlow.scala) { #withBackoff }

@@@

## Description

When an element is emitted by the wrapped `flow` it is passed to the `retryWith` function, which decides whether
one or more retries should be issued. The retry backoff is controlled by the `minBackoff`, `maxBackoff`
and `randomFactor` parameters.

Failures can be retried by returning one or more requests from `retryWith`:

Scala
:   @@snip [RetryFlowSpec.scala](/akka-stream-tests/src/test/scala/akka/stream/scaladsl/RetryFlowSpec.scala) { #retry-failure }

Java
:   @@snip [RetryFlowTest.java](/akka-stream-tests/src/test/java/akka/stream/javadsl/RetryFlowTest.java) { #retry-failure }

The second element of the @scala[Tuple]@java[Pair] that is passed into the `retryWith` function is a pass-through value,
which can be used for different purposes, like:

* to correlate a request with a response
* to track the number of retries
* to store request to be issued in the case of retry

When `retryWith` returns @scala[`Option.none`]@java[`Optional.none`]@scala[ or does not match a response],
no retries will be issued, and the failed or successful response will be emitted downstream.

It is also possible to retry successful responses. In that case the successful response is emitted downstream as well as
retry being scheduled:

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
