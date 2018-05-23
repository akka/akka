# zipWithIndex

Zips elements of current flow with its indices.

@ref[Fan-in operators](../index.md#fan-in-operators)

@@@div { .group-scala }

## Signature

@@signature [Flow.scala]($akka$/akka-stream/src/main/scala/akka/stream/scaladsl/Flow.scala) { #zipWithIndex }

@@@

## Description

Zips elements of current flow with its indices.


@@@div { .callout }

**emits** upstream emits an element and is paired with their index

**backpressures** when downstream backpressures

**completes** when upstream completes

@@@

