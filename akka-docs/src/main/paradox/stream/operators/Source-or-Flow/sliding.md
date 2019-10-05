# sliding

Provide a sliding window over the incoming stream and pass the windows as groups of elements downstream.

@ref[Simple operators](../index.md#simple-operators)

@@@div { .group-scala }

## Signature

@@signature [Flow.scala](/akka-stream/src/main/scala/akka/stream/scaladsl/Flow.scala) { #sliding }

@@@

## Description

Provide a sliding window over the incoming stream and pass the windows as groups of elements downstream.

Note: the last window might be smaller than the requested size due to end of stream.

## Reactive Streams semantics

@@@div { .callout }

**emits** the specified number of elements has been accumulated or upstream completed

**backpressures** when a group has been assembled and downstream backpressures

**completes** when upstream completes

@@@

