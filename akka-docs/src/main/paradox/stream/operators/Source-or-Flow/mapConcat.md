# mapConcat

Transform each element into zero or more elements that are individually passed downstream.

@ref[Simple operators](../index.md#simple-operators)

@@@div { .group-scala }

## Signature

@@signature [Flow.scala](/akka-stream/src/main/scala/akka/stream/scaladsl/Flow.scala) { #mapConcat }

@@@

## Description

Transform each element into zero or more elements that are individually passed downstream.

## Example

Scala
:  @@snip [MapConcat.scala](/akka-docs/src/test/scala/docs/stream/operators/sourceorflow/MapConcat.scala) { #map-concat }

Java
:  @@snip [MapConcat.java](/akka-docs/src/test/java/jdocs/stream/operators/MapConcat.java) { #map-concat }


## Reactive Streams semantics

@@@div { .callout }

**emits** when the mapping function returns an element or there are still remaining elements from the previously calculated collection

**backpressures** when downstream backpressures or there are still available elements from the previously calculated collection

**completes** when upstream completes and all remaining elements has been emitted

@@@

