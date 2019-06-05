# RestartSink.withBackoff

Wrap the given @apidoc[Sink] with a @apidoc[Sink] that will restart it when it fails or complete using an exponential backoff.

@ref[Error handling](../index.md#error-handling)

@@@div { .group-scala }

## Signature

@@signature [RestartSink.scala](/akka-stream/src/main/scala/akka/stream/scaladsl/RestartSink.scala) { #withBackoff }

@@@

## Description

This @apidoc[Sink] will never cancel, since cancellation by the wrapped @apidoc[Sink] is always handled by restarting it.
The wrapped @apidoc[Sink] can however be completed by feeding a completion or error into this @apidoc[Sink]. When that
happens, the @apidoc[Sink], if currently running, will terminate and will not be restarted. This can be triggered
simply by the upstream completing, or externally by introducing a @apidoc[KillSwitch] right before this @apidoc[Sink] in the
graph.

The restart process is inherently lossy, since there is no coordination between cancelling and the sending of
messages. When the wrapped @apidoc[Sink] does cancel, this @apidoc[Sink] will backpressure, however any elements already
sent may have been lost.

This uses the same exponential backoff algorithm as @apidoc[Backoff].
