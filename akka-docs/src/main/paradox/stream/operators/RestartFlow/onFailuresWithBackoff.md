# RestartFlow.onFailuresWithBackoff

Wrap the given @apidoc[Flow] with a @apidoc[Flow] that will restart it when it fails using an exponential backoff. Notice that this @apidoc[Flow] will not restart on completion of the wrapped flow.

@ref[Error handling](../index.md#error-handling)

@@@div { .group-scala }

## Signature

@@signature [RestartFlow.scala](/akka-stream/src/main/scala/akka/stream/scaladsl/RestartFlow.scala) { #onFailuresWithBackoff }

@@@

## Description

This @apidoc[Flow] will not emit any failure
The failures by the wrapped @apidoc[Flow] will be handled by
restarting the wrapping @apidoc[Flow] as long as maxRestarts is not reached.
Any termination signals sent to this @apidoc[Flow] however will terminate the wrapped @apidoc[Flow], if it's
running, and then the @apidoc[Flow] will be allowed to terminate without being restarted.

The restart process is inherently lossy, since there is no coordination between cancelling and the sending of
messages. A termination signal from either end of the wrapped @apidoc[Flow] will cause the other end to be terminated,
and any in transit messages will be lost. During backoff, this @apidoc[Flow] will backpressure.

This uses the same exponential backoff algorithm as @apidoc[Backoff].

## Reactive Streams semantics

@@@div { .callout }

**emits** when the wrapped flow emits

**backpressures** during backoff and when the wrapped flow backpressures

@@@
