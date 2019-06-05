# RestartSource.withBackoff

Wrap the given @apidoc[Source] with a @apidoc[Source] that will restart it when it fails or complete using an exponential backoff.

@ref[Error handling](../index.md#error-handling)

@@@div { .group-scala }

## Signature

@@signature [RestartSource.scala](/akka-stream/src/main/scala/akka/stream/scaladsl/RestartSource.scala) { #withBackoff }

@@@

## Description

This @apidoc[Flow] will never emit a complete or failure, since the completion or failure of the wrapped @apidoc[Source]
is always handled by restarting it. The wrapped @apidoc[Source] can however be cancelled by cancelling this @apidoc[Source].
When that happens, the wrapped @apidoc[Source], if currently running will be cancelled, and it will not be restarted.
This can be triggered simply by the downstream cancelling, or externally by introducing a @apidoc[KillSwitch] right
after this @apidoc[Source] in the graph.

This uses the same exponential backoff algorithm as @apidoc[Backoff].

## Reactive Streams semantics

@@@div { .callout }

**emits** when the wrapped source emits

**completes** when the wrapped source completes

@@@
