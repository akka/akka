# RestartFlow.withBackoff

Wrap the given @apidoc[Flow] with a @apidoc[Flow] that will restart it when it fails or complete using an exponential backoff.

@ref[Error handling](../index.md#error-handling)

## Signature

@apidoc[RestartFlow.withBackoff](RestartFlow$) { scala="#withBackoff[In,Out](settings:akka.stream.RestartSettings)(flowFactory:()=&gt;akka.stream.scaladsl.Flow[In,Out,_]):akka.stream.scaladsl.Flow[In,Out,akka.NotUsed]" java="#withBackoff(akka.stream.RestartSettings,akka.japi.function.Creator)" }

## Description

Wrap the given @apidoc[Flow] with a @apidoc[Flow] that will restart it when it completes or fails using exponential backoff.
The backoff resets back to `minBackoff` if there hasn't been a restart within `maxRestartsWithin`  (which defaults to `minBackoff`).

This @apidoc[Flow] will not cancel, complete or emit a failure, until the opposite end of it has been cancelled or
completed. Any termination by the @apidoc[Flow] before that time will be handled by restarting it as long as maxRestarts
is not reached. Any termination signals sent to this @apidoc[Flow] however will terminate the wrapped @apidoc[Flow], if it's
running, and then the @apidoc[Flow] will be allowed to terminate without being restarted.

The restart process is inherently lossy, since there is no coordination between cancelling and the sending of
messages. A termination signal from either end of the wrapped @apidoc[Flow] will cause the other end to be terminated,
and any in transit messages will be lost. During backoff, this @apidoc[Flow] will backpressure.

This uses the same exponential backoff algorithm as @apidoc[BackoffOpts$].

See also: 
 
* @ref:[RestartSource.withBackoff](../RestartSource/withBackoff.md)
* @ref:[RestartSource.onFailuresWithBackoff](../RestartSource/onFailuresWithBackoff.md)
* @ref:[RestartFlow.onFailuresWithBackoff](../RestartFlow/onFailuresWithBackoff.md)
* @ref:[RestartSink.withBackoff](../RestartSink/withBackoff.md)

## Reactive Streams semantics

@@@div { .callout }

**emits** when the wrapped flow emits

**backpressures** during backoff and when the wrapped flow backpressures

**completes** when `maxRestarts` are reached within the given time limit

@@@
