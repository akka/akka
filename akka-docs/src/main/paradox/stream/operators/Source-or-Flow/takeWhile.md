# takeWhile

Pass elements downstream as long as a predicate function return true for the element include the element when the predicate first return false and then complete.

@ref[Simple operators](../index.md#simple-operators)

@@@div { .group-scala }

## Signature

@@signature [Flow.scala](/akka-stream/src/main/scala/akka/stream/scaladsl/Flow.scala) { #takeWhile }

@@@

## Description

Pass elements downstream as long as a predicate function return true for the element include the element
when the predicate first return false and then complete.

## Reactive Streams semantics

@@@div { .callout }

**emits** while the predicate is true and until the first false result

**backpressures** when downstream backpressures

**completes** when predicate returned false or upstream completes

@@@

