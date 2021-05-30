# Source.single

Stream a single object once.

@ref[Source operators](../index.md#source-operators)

## Signature

@apidoc[Source.single](Source$) { scala="#single[T](element:T):akka.stream.scaladsl.Source[T,akka.NotUsed]" java="#single(T)" }

## Description

Stream a single object once and complete after thereafter.

See also:

* @ref:[`repeat`](repeat.md) Stream a single object repeatedly.
* @ref:[`tick`](tick.md) A periodical repetition of an arbitrary object.
* @ref:[`cycle`](cycle.md) Stream iterator in cycled manner.

## Examples

Scala
:  @@snip [source.scala](/akka-stream-tests/src/test/scala/akka/stream/scaladsl/SourceSpec.scala) { #imports #source-single }

Java
:   @@snip [source.java](/akka-stream-tests/src/test/java/akka/stream/javadsl/SourceTest.java) { #imports #source-single }

## Reactive Streams semantics

@@@div { .callout }

**emits** the value once

**completes** when the single value has been emitted

@@@
