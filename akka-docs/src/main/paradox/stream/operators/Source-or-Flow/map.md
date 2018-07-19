# map

Transform each element in the stream by calling a mapping function with it and passing the returned value downstream.

@ref[Simple operators](../index.md#simple-operators)

@@@div { .group-scala }

## Signature

@@signature [Flow.scala]($akka$/akka-stream/src/main/scala/akka/stream/scaladsl/Flow.scala) { #map }

@@@

## Description

Transform each element in the stream by calling a mapping function with it and passing the returned value downstream.


@@@div { .callout }

**emits** when the mapping function returns an element

**backpressures** when downstream backpressures

**completes** when upstream completes

@@@

## Examples


Scala
:  @@snip [Flow.scala]($akka$/akka-docs/src/test/scala/docs/stream/operators/Map.scala) { #imports #map }



