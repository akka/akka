# mapConcat

Transform each element into zero or more elements that are individually passed downstream.

@ref[Simple processing stages](../index.md#simple-processing-stages)

@@@div { .group-scala }

## Signature

@@signature [Flow.scala]($akka$/akka-stream/src/main/scala/akka/stream/scaladsl/Flow.scala) { #mapConcat }

@@@

## Description

Transform each element into zero or more elements that are individually passed downstream.


@@@div { .callout }

**emits** when the mapping function returns an element or there are still remaining elements from the previously calculated collection

**backpressures** when downstream backpressures or there are still available elements from the previously calculated collection

**completes** when upstream completes and all remaining elements has been emitted

@@@

