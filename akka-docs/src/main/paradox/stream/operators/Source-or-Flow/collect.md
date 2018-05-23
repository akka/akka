# collect

Apply a partial function to each incoming element, if the partial function is defined for a value the returned value is passed downstream.

@ref[Simple operators](../index.md#simple-operators)

@@@div { .group-scala }

## Signature

@@signature [Flow.scala]($akka$/akka-stream/src/main/scala/akka/stream/scaladsl/Flow.scala) { #collect }

@@@

## Description

Apply a partial function to each incoming element, if the partial function is defined for a value the returned
value is passed downstream. Can often replace `filter` followed by `map` to achieve the same in one single operators.


@@@div { .callout }

**emits** when the provided partial function is defined for the element

**backpressures** the partial function is defined for the element and downstream backpressures

**completes** when upstream completes

@@@

