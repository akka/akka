# monitor

Materializes to a `FlowMonitor` that monitors messages flowing through or completion of the stage.

## Signature

## Description

Materializes to a `FlowMonitor` that monitors messages flowing through or completion of the stage. The stage otherwise
passes through elements unchanged. Note that the `FlowMonitor` inserts a memory barrier every time it processes an
event, and may therefore affect performance.


@@@div { .callout }

**emits** when upstream emits an element

**backpressures** when downstream **backpressures**

**completes** when upstream completes

@@@

## Example

