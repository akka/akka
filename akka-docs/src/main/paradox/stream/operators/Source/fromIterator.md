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

## Reactive Streams semantics

@@@div { .callout }

**emits** the next value returned from the iterator

**completes** when the iterator reaches its end

@@@

