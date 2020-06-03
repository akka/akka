# Source.fromIterator

Stream the values from an `Iterator`, requesting the next value when there is demand.

@ref[Source operators](../index.md#source-operators)

## Signature

@apidoc[Source.fromIterator](Source$) { scala="#fromIterator[T](f:()=&gt;Iterator[T]):akka.stream.scaladsl.Source[T,akka.NotUsed]" java="#fromIterator(akka.japi.function.Creator)" }


## Description

Stream the values from an `Iterator`, requesting the next value when there is demand. The iterator will be created anew
for each materialization, which is the reason the @scala[`method`] @java[`factory`] takes a @scala[`function`] @java[`Creator`] rather than an `Iterator` directly.

If the iterator perform blocking operations, make sure to run it on a separate dispatcher.

## Example
 
Scala
:   @@snip [From.scala](/akka-docs/src/test/scala/docs/stream/operators/source/From.scala) { #from-iterator }

Java
:   @@snip [From.java](/akka-docs/src/test/java/jdocs/stream/operators/source/From.java) { #from-iterator }


## Reactive Streams semantics

@@@div { .callout }

**emits** the next value returned from the iterator

**completes** when the iterator reaches its end

@@@

