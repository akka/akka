# interleave

Emits a specifiable number of elements from the original source, then from the provided source and repeats.

@ref[Fan-in operators](../index.md#fan-in-operators)

## Signature

@apidoc[Source.interleave](Source) { scala="#interleave[U&gt;:Out](that:akka.stream.Graph[akka.stream.SourceShape[U],_],segmentSize:Int,eagerClose:Boolean):FlowOps.this.Repr[U]" java="#interleave(akka.stream.Graph,int,boolean)" }
@apidoc[Flow.interleave](Flow) { scala="#interleave[U&gt;:Out](that:akka.stream.Graph[akka.stream.SourceShape[U],_],segmentSize:Int,eagerClose:Boolean):FlowOps.this.Repr[U]" java="#interleave(akka.stream.Graph,int,boolean)" }


## Description

Emits a specifiable number of elements from the original source, then from the provided source and repeats. If one
source completes the rest of the other stream will be emitted.

## Example
Scala
:   @@snip [FlowInterleaveSpec.scala](/akka-stream-tests/src/test/scala/akka/stream/scaladsl/FlowInterleaveSpec.scala) { #interleave }

Java
:   @@snip [SourceOrFlow.java](/akka-docs/src/test/java/jdocs/stream/operators/SourceOrFlow.java) { #interleave }

## Reactive Streams semantics

@@@div { .callout }

**emits** when element is available from the currently consumed upstream

**backpressures** when upstream backpressures

**completes** when both upstreams have completed

@@@
