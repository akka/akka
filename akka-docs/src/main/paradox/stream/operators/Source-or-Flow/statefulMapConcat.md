# statefulMapConcat

Transform each element into zero or more elements that are individually passed downstream.

@ref[Simple processing stages](../index.md#simple-processing-stages)

@@@div { .group-scala }

## Signature

@@signature [Flow.scala]($akka$/akka-stream/src/main/scala/akka/stream/scaladsl/Flow.scala) { #statefulMapConcat }

@@@

## Description

Transform each element into zero or more elements that are individually passed downstream. The difference to `mapConcat` is that
the transformation function is created from a factory for every materialization of the flow.


@@@div { .callout }

**emits** when the mapping function returns an element or there are still remaining elements from the previously calculated collection

**backpressures** when downstream backpressures or there are still available elements from the previously calculated collection

**completes** when upstream completes and all remaining elements has been emitted

@@@

