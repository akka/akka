# mergePrioritized

Merge multiple sources.

@ref[Fan-in operators](../index.md#fan-in-operators)

## Signature

@apidoc[Source.mergePrioritized](Source) { scala="#mergePrioritized[U&gt;:Out,M](that:akka.stream.Graph[akka.stream.SourceShape[U],M],leftPriority:Int,rightPriority:Int,eagerComplete:Boolean):FlowOps.this.Repr[U]" java="#mergePrioritized(akka.stream.Graph,int,int,boolean)" }
@apidoc[Flow.mergePrioritized](Flow) { scala="#mergePrioritized[U&gt;:Out,M](that:akka.stream.Graph[akka.stream.SourceShape[U],M],leftPriority:Int,rightPriority:Int,eagerComplete:Boolean):FlowOps.this.Repr[U]" java="#mergePrioritized(akka.stream.Graph,int,int,boolean)" }

## Description

Merge multiple sources. Prefer sources depending on priorities if all sources have elements ready. If a subset of all
sources have elements ready the relative priorities for those sources are used to prioritize. For example, when used 
with only two sources, the left source has a probability of `(leftPriority) / (leftPriority + rightPriority)` of being 
prioritized and similarly for the right source. The priorities for each source must be positive integers. 

## Example
Scala
:   @@snip [FlowMergeSpec.scala](/akka-stream-tests/src/test/scala/akka/stream/scaladsl/FlowMergeSpec.scala) { #mergePrioritized }

Java
:   @@snip [SourceOrFlow.java](/akka-docs/src/test/java/jdocs/stream/operators/SourceOrFlow.java) { #mergePrioritized }

## Reactive Streams semantics

@@@div { .callout }

**emits** when one of the inputs has an element available, preferring inputs based on their priorities if multiple have elements available

**backpressures** when downstream backpressures

**completes** when all upstreams complete (This behavior is changeable to completing when any upstream completes by setting `eagerComplete=true`.)

@@@

