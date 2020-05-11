# monitor

Materializes to a `FlowMonitor` that monitors messages flowing through or completion of the operators.

@ref[Watching status operators](../index.md#watching-status-operators)

## Signature

@apidoc[Source.monitor](Source) { scala="#monitor[Mat2]()(combine:(Mat,akka.stream.FlowMonitor[Out])=&gt;Mat2):FlowOpsMat.this.ReprMat[Out,Mat2]" java="#monitor(akka.japi.function.Function2)" java="#monitor()" }
@apidoc[Flow.monitor](Flow) { scala="#monitor[Mat2]()(combine:(Mat,akka.stream.FlowMonitor[Out])=&gt;Mat2):FlowOpsMat.this.ReprMat[Out,Mat2]" java="#monitor(akka.japi.function.Function2)" java="#monitor()" }


## Description

Materializes to a `FlowMonitor` that monitors messages flowing through or completion of the operators. The operators otherwise
passes through elements unchanged. Note that the `FlowMonitor` inserts a memory barrier every time it processes an
event, and may therefore affect performance.

## Reactive Streams semantics

@@@div { .callout }

**emits** when upstream emits an element

**backpressures** when downstream **backpressures**

**completes** when upstream completes

@@@

