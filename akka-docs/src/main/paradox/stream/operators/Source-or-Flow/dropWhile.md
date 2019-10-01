# dropWhile

Drop elements as long as a predicate function return true for the element

@ref[Simple operators](../index.md#simple-operators)

@@@div { .group-scala }

## Signature

@@signature [Flow.scala](/akka-stream/src/main/scala/akka/stream/scaladsl/Flow.scala) { #dropWhile }

@@@

## Description

Drop elements as long as a predicate function return true for the element

## Reactive Streams semantics

@@@div { .callout }

**emits** when the predicate returned false and for all following stream elements

**backpressures** predicate returned false and downstream backpressures

**completes** when upstream completes

@@@

