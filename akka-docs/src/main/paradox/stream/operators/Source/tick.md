# Source.tick

A periodical repetition of an arbitrary object.

@ref[Source operators](../index.md#source-operators)

## Signature

@apidoc[Source.tick](Source$) { scala="#tick[T](initialDelay:scala.concurrent.duration.FiniteDuration,interval:scala.concurrent.duration.FiniteDuration,tick:T):akka.stream.scaladsl.Source[T,akka.actor.Cancellable]" java="#tick(java.time.Duration,java.time.Duration,java.lang.Object)" }


## Description

A periodical repetition of an arbitrary object. Delay of first tick is specified
separately from interval of the following ticks. 

If downstream is applying backpressure when the time period has passed the tick is dropped.

The source materializes a @apidoc[Cancellable] that can be used to complete the source.

@@@note

The element must be immutable as the source can be materialized several times and may pass it between threads, see the second 
example for achieving a periodical element that changes over time.

@@@

See also:

* @ref:[`repeat`](repeat.md) Stream a single object repeatedly.
* @ref:[`cycle`](cycle.md) Stream iterator in cycled manner.

## Examples

This first example prints to standard out periodically:

Scala
:   @@snip [Tick.scala](/akka-docs/src/test/scala/docs/stream/operators/source/Tick.scala) { #simple }

Java
:   @@snip [Tick.java](/akka-docs/src/test/java/jdocs/stream/operators/source/Tick.java) { #simple }

You can also use the tick to periodically emit a value, in this sample we use the tick to trigger a query to an
actor using @ref:[ask](../../../typed/interaction-patterns.md#outside-ask) and emit the response downstream. For this
usage, what is important is that it was emitted, not the actual tick value.

Scala
:   @@snip [Tick.scala](/akka-docs/src/test/scala/docs/stream/operators/source/Tick.scala) { #poll-actor }

Java
:   @@snip [Tick.java](/akka-docs/src/test/java/jdocs/stream/operators/source/Tick.java) { #poll-actor }

A neat trick is to combine this with @ref:[zipLatest](../Source-or-Flow/zipLatest.md) to combine a stream of elements
with a value that is updated periodically instead of having to trigger a query for each element:

Scala
:   @@snip [Tick.scala](/akka-docs/src/test/scala/docs/stream/operators/source/Tick.scala) { #zip-latest }

Java
:   @@snip [Tick.java](/akka-docs/src/test/java/jdocs/stream/operators/source/Tick.java) { #zip-latest }

## Reactive Streams semantics

@@@div { .callout }

**emits** periodically, if there is downstream backpressure ticks are skipped

**completes** when the materialized `Cancellable` is cancelled

@@@
