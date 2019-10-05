# grouped

Accumulate incoming events until the specified number of elements have been accumulated and then pass the collection of elements downstream.

@ref[Simple operators](../index.md#simple-operators)

@@@div { .group-scala }

## Signature

@@signature [Flow.scala](/akka-stream/src/main/scala/akka/stream/scaladsl/Flow.scala) { #grouped }

@@@

## Description

Accumulate incoming events until the specified number of elements have been accumulated and then pass the collection of
elements downstream.

## Reactive Streams semantics

@@@div { .callout }

**emits** when the specified number of elements has been accumulated or upstream completed

**backpressures** when a group has been assembled and downstream backpressures

**completes** when upstream completes

@@@

