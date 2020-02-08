# fromIterator

Stream the values from an `Iterator`, requesting the next value when there is demand.

@ref[Source operators](../index.md#source-operators)

@@@div { .group-scala }

## Signature

@@signature [Source.scala](/akka-stream/src/main/scala/akka/stream/scaladsl/Source.scala) { #fromIterator }

@@@

## Description

Stream the values from an `Iterator`, requesting the next value when there is demand. The iterator will be created anew
for each materialization, which is the reason the @scala[`method`] @java[`factory`] takes a @scala[`function`] @java[`Creator`] rather than an `Iterator` directly.

If the iterator perform blocking operations, make sure to run it on a separate dispatcher.

## Example

In this sample we create infinite cyclic `Iterator` where each element is true or false.  
Note that due to using `grouped(10)` and `Sink.head` stream gets only first ten values from iterator. 

Scala
:   @@snip [SourceSpec.scala](/akka-stream-tests/src/test/scala/akka/stream/scaladsl/SourceSpec.scala) { #iterate-simple }

Java
:   @@snip [SourceTest.java](/akka-stream-tests/src/test/java/akka/stream/javadsl/SourceTest.java) { #iterate-simple }


## Reactive Streams semantics

@@@div { .callout }

**emits** the next value returned from the iterator

**completes** when the iterator reaches its end

@@@

