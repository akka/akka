# flattenMerge

Flatten merge each input `Source`'s output elements into the output stream by merging,where at most `breadth` substreams are being consumed at any given time,this method is equivalent to `flatMapMerge(breadth, identity)`.   
   
@ref[Nesting and flattening operators](../index.md#nesting-and-flattening-operators)

@@@div { .group-scala }

## Signature

@@signature [Flow.scala](/akka-stream/src/main/scala/akka/stream/scaladsl/Flow.scala) { #flattenMerge }

@@@

## Description

Flatten merge each input `Source`'s output elements into the output stream by merging,
where at most `breadth` substreams are being consumed at any given time,
this method is equivalent to `flatMapMerge(breadth, identity)`.  

## Reactive Streams semantics

@@@div { .callout }

**emits** when one of the currently consumed substreams has an element available

**backpressures** when downstream backpressures

**completes** when upstream completes and all consumed substreams complete

@@@


