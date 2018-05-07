# collect

Apply a partial function to each incoming element, if the partial function is defined for a value the returned value is passed downstream.

@ref[Simple processing stages](../index.md#simple-processing-stages)

@@@div { .group-scala }

## Signature

@@signature [Flow.scala]($akka$/akka-stream/src/main/scala/akka/stream/scaladsl/Flow.scala) { #collect }

@@@

## Description

Apply a partial function to each incoming element, if the partial function is defined for a value the returned
value is passed downstream. Can often replace `filter` followed by `map` to achieve the same in one single stage.


@@@div { .callout }

**emits** when the provided partial function is defined for the element

**backpressures** the partial function is defined for the element and downstream backpressures

**completes** when upstream completes

@@@

