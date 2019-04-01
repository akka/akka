# RestartSink.withBackoff

Wrap the given @unidoc[Sink] with a @unidoc[Sink] that will restart it when it fails or complete using an exponential backoff.

@ref[Error handling](../index.md#error-handling)

@@@div { .group-scala }

## Signature

@@signature [RestartSink.scala](/akka-stream/src/main/scala/akka/stream/scaladsl/RestartSink.scala) { #withBackoff }

@@@

## Description

This @unidoc[Sink] will never cancel, since cancellation by the wrapped @unidoc[Sink] is always handled by restarting it.
The wrapped @unidoc[Sink] can however be completed by feeding a completion or error into this @unidoc[Sink]. When that
happens, the @unidoc[Sink], if currently running, will terminate and will not be restarted. This can be triggered
simply by the upstream completing, or externally by introducing a @unidoc[KillSwitch] right before this @unidoc[Sink] in the
graph.

The restart process is inherently lossy, since there is no coordination between cancelling and the sending of
messages. When the wrapped @unidoc[Sink] does cancel, this @unidoc[Sink] will backpressure, however any elements already
sent may have been lost.

This uses the same exponential backoff algorithm as @unidoc[Backoff].
