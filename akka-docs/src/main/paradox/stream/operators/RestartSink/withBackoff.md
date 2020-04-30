# RestartSink.withBackoff

Wrap the given @apidoc[Sink] with a @apidoc[Sink] that will restart it when it fails or complete using an exponential backoff.

@ref[Error handling](../index.md#error-handling)

## Signature

@apidoc[RestartSink.withBackoff](RestartSink$) { scala="#withBackoff[T](minBackoff:scala.concurrent.duration.FiniteDuration,maxBackoff:scala.concurrent.duration.FiniteDuration,randomFactor:Double,maxRestarts:Int)(sinkFactory:()=&gt;akka.stream.scaladsl.Sink[T,_]):akka.stream.scaladsl.Sink[T,akka.NotUsed]"  java="#withBackoff(java.time.Duration,java.time.Duration,double,int,akka.japi.function.Creator)" }


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
