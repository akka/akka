# RestartSource.onFailuresWithBackoff

Wrap the given @apidoc[Source] with a @apidoc[Source] that will restart it when it fails using an exponential backoff.

@ref[Error handling](../index.md#error-handling)

## Signature

@apidoc[RestartSource.onFailuresWithBackoff](RestartSource$) { scala="#onFailuresWithBackoff[T](minBackoff:scala.concurrent.duration.FiniteDuration,maxBackoff:scala.concurrent.duration.FiniteDuration,randomFactor:Double)(sourceFactory:()=&gt;akka.stream.scaladsl.Source[T,_]):akka.stream.scaladsl.Source[T,akka.NotUsed]" java="#onFailuresWithBackoff(java.time.Duration,java.time.Duration,double,int,akka.japi.function.Creator)" }

## Description

Wraps the given @apidoc[Source] with a @apidoc[Source] that will restart it when it fails using an exponential backoff.
The backoff resets back to `minBackoff` if there hasn't been a failure within `minBackoff`.
 
This @apidoc[Source] will never emit a failure, since the failure of the wrapped @apidoc[Source] is always handled by
restarting. The wrapped @apidoc[Source] can be completed by completing this @apidoc[Source].
When that happens, the wrapped @apidoc[Source], if currently running will be cancelled, and it will not be restarted.
This can be triggered by the downstream cancelling, or externally by introducing a @ref[KillSwitch](../../stream-dynamic.md#controlling-stream-completion-with-killswitch) right
after this @apidoc[Source] in the graph.

See also: 
 
* @ref:[RestartFlow.onFailuresWithBackoff](../RestartFlow/onFailuresWithBackoff.md)
* @ref:[RestartFlow.withBackoff](../RestartFlow/withBackoff.md)
* @ref:[RestartSink.withBackoff](../RestartSink/withBackoff.md)

## Examples

This shows that a Source is not restarted if it completes, only if it fails. Tick is only printed
three times as the `take(3)` means the inner source completes successfully after emitting the first 3 elements.

Scala
:  @@snip [Restart.scala](/akka-docs/src/test/scala/docs/stream/operators/source/Restart.scala) { #restart-failure-inner-complete }

Java
:  @@snip [Restart.java](/akka-docs/src/test/java/jdocs/stream/operators/source/Restart.java) { #restart-failure-inner-complete }

If the inner source instead fails, it will be restarted with an increasing backoff. The source emits 1, 2, 3, and then throws an exception.
The first time the exception is thrown the source is restarted after 1s, then 2s etc, until the `maxBackoff` of 10s.

Scala
:  @@snip [Restart.scala](/akka-docs/src/test/scala/docs/stream/operators/source/Restart.scala) { #restart-failure-inner-failure }

Java
:  @@snip [Restart.java](/akka-docs/src/test/java/jdocs/stream/operators/source/Restart.java) { #restart-failure-inner-failure }

Finally, to be able to stop the restarting, a kill switch can be used. The kill switch is inserted right after the restart
source. The inner source is the same as above so emits 3 elements and then fails. A killswitch is used to be able to stop the source
being restarted: 

Scala
:  @@snip [Restart.scala](/akka-docs/src/test/scala/docs/stream/operators/source/Restart.scala) { #restart-failure-inner-complete-kill-switch }

Java
:  @@snip [Restart.java](/akka-docs/src/test/java/jdocs/stream/operators/source/Restart.java) { #restart-failure-inner-complete-kill-switch }

## Reactive Streams semantics

@@@div { .callout }

**emits** when the wrapped source emits

@@@
