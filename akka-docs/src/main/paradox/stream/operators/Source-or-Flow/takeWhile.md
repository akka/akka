# takeWhile

Pass elements downstream as long as a predicate function returns true and then complete. 

@ref[Simple operators](../index.md#simple-operators)

@@@div { .group-scala }

## Signature

@@signature [Flow.scala](/akka-stream/src/main/scala/akka/stream/scaladsl/Flow.scala) { #takeWhile }

@@@

## Description

Pass elements downstream as long as a predicate function returns true and then complete. 
The element for which the predicate returns false is not emitted. 

## Example

Scala
:  @@snip [TakeWhile.scala](/akka-docs/src/test/scala/docs/stream/operators/sourceorflow/TakeWhile.scala) { #take-while }

Java
:   @@snip [SourceOrFlow.java](/akka-docs/src/test/java/jdocs/stream/operators/SourceOrFlow.java) { #take-while }

## Reactive Streams semantics

@@@div { .callout }

**emits** while the predicate is true and until the first false result

**backpressures** when downstream backpressures

**completes** when predicate returned false or upstream completes

@@@
