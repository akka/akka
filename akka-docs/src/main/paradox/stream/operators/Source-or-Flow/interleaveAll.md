# interleaveAll

Emits a specifiable number of elements from the original source, then from the provided sources and repeats.

@ref[Fan-in operators](../index.md#fan-in-operators)

## Signature

@apidoc[Source.interleaveAll](Source) { scala="#interleaveAll[U&gt;:Out](that:List[akka.stream.Graph[akka.stream.SourceShape[U],_]],segmentSize:Int,eagerClose:Boolean):FlowOps.this.Repr[U]" java="#interleaveAll(java.util.List[akka.stream.Graph],int,boolean)" }
@apidoc[Flow.interleaveAll](Flow) { scala="#interleaveAll[U&gt;:Out](that:List[akka.stream.Graph[akka.stream.SourceShape[U],_]],segmentSize:Int,eagerClose:Boolean):FlowOps.this.Repr[U]" java="#interleaveAll(java.util.List[akka.stream.Graph],int,boolean)" }


## Description

Emits a specifiable number of elements from the original source, then from the provided sources and repeats.
If one source completes the rest of the other stream will be emitted when `eagerClose` is false, otherwise 
the flow is complete.

## Example
Scala
:   @@snip [FlowInterleaveSpec.scala](/akka-stream-tests/src/test/scala/akka/stream/scaladsl/FlowInterleaveAllSpec.scala) { #interleaveAll }

Java
:   @@snip [SourceOrFlow.java](/akka-docs/src/test/java/jdocs/stream/operators/SourceOrFlow.java) { #interleaveAll }

## Reactive Streams semantics

@@@div { .callout }

**emits** when element is available from the currently consumed upstream

**backpressures** when upstream backpressures

**completes** when all upstreams have completed if `eagerClose` is false, or any upstream completes if `eagerClose` is true.

@@@
