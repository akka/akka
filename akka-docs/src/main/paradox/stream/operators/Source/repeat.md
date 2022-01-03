# Source.repeat

Stream a single object repeatedly.

@ref[Source operators](../index.md#source-operators)

## Signature

@apidoc[Source.repeat](Source$) { scala="#repeat[T](element:T):akka.stream.scaladsl.Source[T,akka.NotUsed]" java="#repeat(T)" }

## Description

This source emits a single element repeatedly. It never completes, if you want the stream to be finite you will need to limit it by combining with another operator

See also:

* @ref:[`single`](single.md) Stream a single object once.
* @ref:[`tick`](tick.md) A periodical repetition of an arbitrary object.
* @ref:[`cycle`](cycle.md) Stream iterator in cycled manner.

## Example

This example prints the first 4 elements emitted by `Source.repeat`.

Scala
:  @@snip [snip](/akka-stream-tests/src/test/scala/akka/stream/scaladsl/SourceSpec.scala) { #repeat }

Java
:  @@snip [snip](/akka-stream-tests/src/test/java/akka/stream/javadsl/SourceTest.java) { #repeat }



## Reactive Streams semantics

@@@div { .callout }

**emits** the same value repeatedly when there is demand

**completes** never

@@@

