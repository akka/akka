# zipWith

Combines elements from multiple sources through a `combine` function and passes the returned value downstream.

@ref[Fan-in operators](../index.md#fan-in-operators)

@@@div { .group-scala }

## Signature

@@signature [Flow.scala]($akka$/akka-stream/src/main/scala/akka/stream/scaladsl/Flow.scala) { #zipWith }

@@@

## Description

Combines elements from multiple sources through a `combine` function and passes the
returned value downstream.


@@@div { .callout }

**emits** when all of the inputs have an element available

**backpressures** when downstream backpressures

**completes** when any upstream completes

@@@

