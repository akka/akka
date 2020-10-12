# RestartSink.withBackoff

Wrap the given @apidoc[Sink] with a @apidoc[Sink] that will restart it when it fails or complete using an exponential backoff.

@ref[Error handling](../index.md#error-handling)

## Signature

@apidoc[RestartSink.withBackoff](RestartSink$) { scala="#withBackoff[T](settings:akka.stream.RestartSettings)(sinkFactory:()=&gt;akka.stream.scaladsl.Sink[T,_]):akka.stream.scaladsl.Sink[T,akka.NotUsed]"  java="#withBackoff(akka.stream.RestartSettings,akka.japi.function.Creator)" }

## Description

Wrap the given @apidoc[Sink] with a @apidoc[Sink] that will restart it when it completes or fails using exponential backoff.
The backoff resets back to `minBackoff` if there hasn't been a restart within `maxRestartsWithin`  (which defaults to `minBackoff`).

This @apidoc[Sink] will not cancel as long as maxRestarts is not reached, since cancellation by the wrapped @apidoc[Sink]
is handled by restarting it. The wrapped @apidoc[Sink] can however be completed by feeding a completion or error into
this @apidoc[Sink]. When that happens, the @apidoc[Sink], if currently running, will terminate and will not be restarted.
This can be triggered simply by the upstream completing, or externally by introducing a @ref[KillSwitch](../../stream-dynamic.md#controlling-stream-completion-with-killswitch) right
before this @apidoc[Sink] in the graph.

The restart process is inherently lossy, since there is no coordination between cancelling and the sending of
messages. When the wrapped @apidoc[Sink] does cancel, this @apidoc[Sink] will backpressure, however any elements already
sent may have been lost.

This uses the same exponential backoff algorithm as @apidoc[BackoffOpts$].

See also: 
 
* @ref:[RestartSource.withBackoff](../RestartSource/withBackoff.md)
* @ref:[RestartSource.onFailuresWithBackoff](../RestartSource/onFailuresWithBackoff.md)
* @ref:[RestartFlow.onFailuresWithBackoff](../RestartFlow/onFailuresWithBackoff.md)
* @ref:[RestartFlow.withBackoff](../RestartFlow/withBackoff.md)

## Reactive Streams semantics

@@@div { .callout }

**backpressures** during backoff and when the wrapped sink backpressures

**completes** when upstream completes or when `maxRestarts` are reached within the given time limit

@@@
