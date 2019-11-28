# filter

Filter the incoming elements using a predicate.

@ref[Simple operators](../index.md#simple-operators)

@@@div { .group-scala }

## Signature

@@signature [Flow.scala](/akka-stream/src/main/scala/akka/stream/scaladsl/Flow.scala) { #filter }

@@@

## Description

Filter the incoming elements using a predicate. If the predicate returns true the element is passed downstream, if
it returns false the element is discarded.

See also @ref:[`filterNot`](filterNot.md).

## Example

For example, given a `Source` of words we can select the longer words with the `filter` operator: 

Scala
:  @@snip [Filter.scala](/akka-docs/src/test/scala/docs/stream/operators/sourceorflow/Filter.scala) { #filter }

Java
:  @@snip [SourceOrFlow.java](/akka-docs/src/test/java/jdocs/stream/operators/SourceOrFlow.java) { #filter }

## Reactive Streams semantics

@@@div { .callout }

**emits** when the given predicate returns true for the element

**backpressures** when the given predicate returns true for the element and downstream backpressures

**completes** when upstream completes

@@@

