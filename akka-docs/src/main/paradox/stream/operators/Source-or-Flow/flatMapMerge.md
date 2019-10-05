# flatMapMerge

Transform each input element into a `Source` whose elements are then flattened into the output stream through merging.

@ref[Nesting and flattening operators](../index.md#nesting-and-flattening-operators)

@@@div { .group-scala }

## Signature

@@signature [Flow.scala](/akka-stream/src/main/scala/akka/stream/scaladsl/Flow.scala) { #flatMapMerge }

@@@

## Description

Transform each input element into a `Source` whose elements are then flattened into the output stream through
merging. The maximum number of merged sources has to be specified.

## Reactive Streams semantics

@@@div { .callout }

**emits** when one of the currently consumed substreams has an element available

**backpressures** when downstream backpressures

**completes** when upstream completes and all consumed substreams complete

@@@


