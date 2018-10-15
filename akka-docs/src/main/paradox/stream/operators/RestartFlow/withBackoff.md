# RestartFlow.withBackoff

Wrap the given @unidoc[Flow] with a @unidoc[Flow] that will restart it when it fails or complete using an exponential backoff.

@ref[Error handling](../index.md#error-handling)

@@@div { .group-scala }

## Signature

@@signature [RestartFlow.scala](/akka-stream/src/main/scala/akka/stream/scaladsl/RestartFlow.scala) { #withBackoff }

@@@

## Description

The resulting @unidoc[Flow] will not cancel, complete or emit a failure, until the opposite end of it has been cancelled or
completed. Any termination by the @unidoc[Flow] before that time will be handled by restarting it. Any termination
signals sent to this @unidoc[Flow] however will terminate the wrapped @unidoc[Flow], if it's running, and then the @unidoc[Flow]
will be allowed to terminate without being restarted.

The restart process is inherently lossy, since there is no coordination between cancelling and the sending of
messages. A termination signal from either end of the wrapped @unidoc[Flow] will cause the other end to be terminated,
and any in transit messages will be lost. During backoff, this @unidoc[Flow] will backpressure.

This uses the same exponential backoff algorithm as @unidoc[Backoff].

## Reactive Streams semantics

@@@div { .callout }

**emits** when the wrapped flow emits

**backpressures** during backoff and when the wrapped flow backpressures

**completes** when the wrapped flow completes

@@@
