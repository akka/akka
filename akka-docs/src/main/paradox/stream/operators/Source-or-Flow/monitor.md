# monitor

Materializes to a `FlowMonitor` that monitors messages flowing through or completion of the stage.

@ref[Watching status stages](../index.md#watching-status-stages)

@@@div { .group-scala }

## Signature

@@signature [Flow.scala]($akka$/akka-stream/src/main/scala/akka/stream/scaladsl/Flow.scala) { #monitor }

@@@

## Description

Materializes to a `FlowMonitor` that monitors messages flowing through or completion of the stage. The stage otherwise
passes through elements unchanged. Note that the `FlowMonitor` inserts a memory barrier every time it processes an
event, and may therefore affect performance.


@@@div { .callout }

**emits** when upstream emits an element

**backpressures** when downstream **backpressures**

**completes** when upstream completes

@@@

