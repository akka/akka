# RestartFlow.onFailuresWithBackoff

Wrap the given @apidoc[Flow] with a @apidoc[Flow] that will restart it when it fails using an exponential backoff. Notice that this @apidoc[Flow] will not restart on completion of the wrapped flow.

@ref[Error handling](../index.md#error-handling)

## Signature

@apidoc[RestartFlow.onFailuresWithBackoff](RestartFlow$) { scala="#onFailuresWithBackoff[In,Out](settings:akka.stream.RestartSettings)(flowFactory:()=&gt;akka.stream.scaladsl.Flow[In,Out,_]):akka.stream.scaladsl.Flow[In,Out,akka.NotUsed]" java="#onFailuresWithBackoff(akka.stream.RestartSettings,akka.japi.function.Creator)" }


## Description

Wrap the given @apidoc[Flow] with a @apidoc[Flow] that will restart it when it fails using exponential backoff.
The backoff resets back to `minBackoff` if there hasn't been a restart within `maxRestartsWithin` (which defaults to `minBackoff` if max restarts).

This @apidoc[Flow] will not emit any failure as long as maxRestarts is not reached.
The failure of the wrapped @apidoc[Flow] will be handled by restarting it.
However, any termination signals sent to this @apidoc[Flow] will terminate the wrapped @apidoc[Flow], if it's
running, and then the @apidoc[Flow] will be allowed to terminate without being restarted.

The restart process is inherently lossy, since there is no coordination between cancelling and the sending of
messages. A termination signal from either end of the wrapped @apidoc[Flow] will cause the other end to be terminated,
and any in transit messages will be lost. During backoff, this @apidoc[Flow] will backpressure.

This uses the same exponential backoff algorithm as @apidoc[BackoffOpts$].

See also: 
 
* @ref:[RestartSource.withBackoff](../RestartSource/withBackoff.md)
* @ref:[RestartSource.onFailuresWithBackoff](../RestartSource/onFailuresWithBackoff.md)
* @ref:[RestartFlow.withBackoff](../RestartFlow/withBackoff.md)
* @ref:[RestartSink.withBackoff](../RestartSink/withBackoff.md)

## Reactive Streams semantics

@@@div { .callout }

**emits** when the wrapped flow emits

**backpressures** during backoff and when the wrapped flow backpressures

**completes** when the wrapped flow completes or `maxRestarts` are reached within the given time limit

@@@
