# RestartSource.onFailuresWithBackoff

Wrap the given @unidoc[Source] with a @unidoc[Source] that will restart it when it fails using an exponential backoff.

@ref[Error handling](../index.md#error-handling)

@@@div { .group-scala }

## Signature

@@signature [RestartSource.scala](/akka-stream/src/main/scala/akka/stream/scaladsl/RestartSource.scala) { #onFailuresWithBackoff }

@@@

## Description

 This @unidoc[Source] will never emit a failure, since the failure of the wrapped @unidoc[Source] is always handled by
 restarting. The wrapped @unidoc[Source] can be cancelled by cancelling this @unidoc[Source].
 When that happens, the wrapped @unidoc[Source], if currently running will be cancelled, and it will not be restarted.
 This can be triggered simply by the downstream cancelling, or externally by introducing a @unidoc[KillSwitch] right
 after this @unidoc[Source] in the graph.

## Reactive Streams semantics

@@@div { .callout }

**emits** when the wrapped source emits

@@@
