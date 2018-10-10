# RestartSource.withBackoff

Wrap the given @unidoc[Source] with a @unidoc[Source] that will restart it when it fails or complete using an exponential backoff.

@ref[Error handling](../index.md#error-handling)

@@@div { .group-scala }

## Signature

@@signature [RestartSource.scala](/akka-stream/src/main/scala/akka/stream/scaladsl/RestartSource.scala) { #withBackoff }

@@@

## Description

This @unidoc[Flow] will never emit a complete or failure, since the completion or failure of the wrapped @unidoc[Source]
is always handled by restarting it. The wrapped @unidoc[Source] can however be cancelled by cancelling this @unidoc[Source].
When that happens, the wrapped @unidoc[Source], if currently running will be cancelled, and it will not be restarted.
This can be triggered simply by the downstream cancelling, or externally by introducing a @unidoc[KillSwitch] right
after this @unidoc[Source] in the graph.

This uses the same exponential backoff algorithm as @unidoc[Backoff].

## Reactive Streams semantics

@@@div { .callout }

**emits** when the wrapped source emits

**completes** when the wrapped source completes

@@@
