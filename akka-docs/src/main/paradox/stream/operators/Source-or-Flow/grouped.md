# grouped

Accumulate incoming events until the specified number of elements have been accumulated and then pass the collection of elements downstream.

@ref[Simple processing stages](../index.md#simple-processing-stages)

@@@div { .group-scala }

## Signature

@@signature [Flow.scala]($akka$/akka-stream/src/main/scala/akka/stream/scaladsl/Flow.scala) { #grouped }

@@@

## Description

Accumulate incoming events until the specified number of elements have been accumulated and then pass the collection of
elements downstream.


@@@div { .callout }

**emits** when the specified number of elements has been accumulated or upstream completed

**backpressures** when a group has been assembled and downstream backpressures

**completes** when upstream completes

@@@

