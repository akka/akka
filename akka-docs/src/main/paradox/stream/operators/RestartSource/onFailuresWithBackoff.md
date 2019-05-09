# RestartSource.onFailuresWithBackoff

Wrap the given @apidoc[Source] with a @apidoc[Source] that will restart it when it fails using an exponential backoff.

@ref[Error handling](../index.md#error-handling)

@@@div { .group-scala }

## Signature

@@signature [RestartSource.scala](/akka-stream/src/main/scala/akka/stream/scaladsl/RestartSource.scala) { #onFailuresWithBackoff }

@@@

## Description

 This @apidoc[Source] will never emit a failure, since the failure of the wrapped @apidoc[Source] is always handled by
 restarting. The wrapped @apidoc[Source] can be cancelled by cancelling this @apidoc[Source].
 When that happens, the wrapped @apidoc[Source], if currently running will be cancelled, and it will not be restarted.
 This can be triggered simply by the downstream cancelling, or externally by introducing a @apidoc[KillSwitch] right
 after this @apidoc[Source] in the graph.

## Reactive Streams semantics

@@@div { .callout }

**emits** when the wrapped source emits

@@@
