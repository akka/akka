# limitWeighted

Ensure stream boundedness by evaluating the cost of incoming elements using a cost function.

@ref[Simple operators](../index.md#simple-operators)

@@@div { .group-scala }

## Signature

@@signature [Flow.scala]($akka$/akka-stream/src/main/scala/akka/stream/scaladsl/Flow.scala) { #limitWeighted }

@@@

## Description

Ensure stream boundedness by evaluating the cost of incoming elements using a cost function.
Evaluated cost of each element defines how many elements will be allowed to travel downstream.


@@@div { .callout }

**emits** when upstream emits and the number of emitted elements has not reached max

**backpressures** when downstream backpressures

**completes** when upstream completes and the number of emitted elements has not reached max

@@@

