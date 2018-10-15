# RestartFlow.onFailuresWithBackoff

Wrap the given @unidoc[Flow] with a @unidoc[Flow] that will restart it when it fails using an exponential backoff. Notice that this @unidoc[Flow] will not restart on completion of the wrapped flow.

@ref[Error handling](../index.md#error-handling)

@@@div { .group-scala }

## Signature

@@signature [RestartFlow.scala](/akka-stream/src/main/scala/akka/stream/scaladsl/RestartFlow.scala) { #onFailuresWithBackoff }

@@@

## Description

This @unidoc[Flow] will not emit any failure
The failures by the wrapped @unidoc[Flow] will be handled by
restarting the wrapping @unidoc[Flow] as long as maxRestarts is not reached.
Any termination signals sent to this @unidoc[Flow] however will terminate the wrapped @unidoc[Flow], if it's
running, and then the @unidoc[Flow] will be allowed to terminate without being restarted.

The restart process is inherently lossy, since there is no coordination between cancelling and the sending of
messages. A termination signal from either end of the wrapped @unidoc[Flow] will cause the other end to be terminated,
and any in transit messages will be lost. During backoff, this @unidoc[Flow] will backpressure.

This uses the same exponential backoff algorithm as @unidoc[Backoff].

## Reactive Streams semantics

@@@div { .callout }

**emits** when the wrapped flow emits

**backpressures** during backoff and when the wrapped flow backpressures

@@@
