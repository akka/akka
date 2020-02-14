# dropWhile

Drop elements as long as a predicate function return true for the element

@ref[Simple operators](../index.md#simple-operators)

@@@div { .group-scala }

## Signature

@@signature [Flow.scala](/akka-stream/src/main/scala/akka/stream/scaladsl/Flow.scala) { #dropWhile }

@@@

## Description

Drop elements as long as a predicate function return true for the element

## Example

Given a `Source` of ordered numbers we can drop all the negative ones with the `dropWhile` operator. 
Mind that after the first non negative number is encountered, all the consecutive elements will be emitted despite the predicate provided.  

Scala
:  @@snip [Drop.scala](/akka-docs/src/test/scala/docs/stream/operators/sourceorflow/Drop.scala) { #dropWhile }

Java
:  @@snip [SourceOrFlow.java](/akka-docs/src/test/java/jdocs/stream/operators/SourceOrFlow.java) { #dropWhile }

## Reactive Streams semantics

@@@div { .callout }

**emits** when the predicate returned false and for all following stream elements

**backpressures** predicate returned false and downstream backpressures

**completes** when upstream completes

@@@

