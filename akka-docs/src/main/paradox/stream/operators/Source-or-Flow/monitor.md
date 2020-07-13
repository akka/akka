# monitor

Materializes to a `FlowMonitor` that monitors messages flowing through or completion of the operators.

@ref[Watching status operators](../index.md#watching-status-operators)

## Signature

@apidoc[Source.monitor](Source) { scala="#monitor[Mat2]()(combine:(Mat,akka.stream.FlowMonitor[Out])=&gt;Mat2):FlowOpsMat.this.ReprMat[Out,Mat2]" java="#monitor()" }
@apidoc[Flow.monitor](Flow) { scala="#monitor[Mat2]()(combine:(Mat,akka.stream.FlowMonitor[Out])=&gt;Mat2):FlowOpsMat.this.ReprMat[Out,Mat2]" java="#monitor()" }


## Description

Materializes to a `FlowMonitor` that monitors messages flowing through or completion of the stream. Elements 
pass through unchanged. Note that the `FlowMonitor` inserts a memory barrier every time it processes an
event, and may therefore affect performance. The provided `FlowMonitor` contains a `state` field you can use to peek
and get information about the stream. 

## Example

The example below uses the `monitorMat` variant of `monitor`. The only difference between the two operators is 
that `monitorMat` has a `combine` argument so we can decide which materialization value to keep. In the sample 
below be `Keep.right` so only the `FlowMonitor[Int]` is returned. 

Scala
:   @@snip [Monitor.scala](/akka-docs/src/test/scala/docs/stream/operators/sourceorflow/Monitor.scala) { #monitor }

Java
:   @@snip [Monitor.java](/akka-docs/src/test/java/jdocs/stream/operators/sourceorflow/Monitor.java) { #monitor }

When run, the sample code will produce something similar to:

```
Stream is initialized but hasn't processed any element
0
1
2
Last element processed: 2
3
4
5
Stream completed already
``` 


## Reactive Streams semantics

@@@div { .callout }

**emits** when upstream emits an element

**backpressures** when downstream **backpressures**

**completes** when upstream completes

@@@

