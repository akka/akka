# mergePreferred

Merge multiple sources.

@ref[Fan-in operators](../index.md#fan-in-operators)

## Signature

@apidoc[Source.mergePreferred](Source) { scala="#mergePreferred[U&gt;:Out,M](that:akka.stream.Graph[akka.stream.SourceShape[U],M],preferred:Boolean,eagerComplete:Boolean):FlowOps.this.Repr[U]" java="#mergePreferred(akka.stream.Graph,boolean,boolean)" }
@apidoc[Flow.mergePreferred](Flow) { scala="#mergePreferred[U&gt;:Out,M](that:akka.stream.Graph[akka.stream.SourceShape[U],M],preferred:Boolean,eagerComplete:Boolean):FlowOps.this.Repr[U]" java="#mergePreferred(akka.stream.Graph,boolean,boolean)" }

## Description

Merge multiple sources. If all sources have elements ready, emit the preferred source first. Then emit the
preferred source again if another element is pushed. Otherwise, emit all the secondary sources. Repeat until streams
are empty. For the case with two sources, when `preferred` is set to true then prefer the right source, otherwise 
prefer the left source (see examples).

## Example
Scala
:   @@snip [FlowMergeSpec.scala](/akka-stream-tests/src/test/scala/akka/stream/scaladsl/FlowMergeSpec.scala) { #mergePreferred }

Java
:   @@snip [SourceOrFlow.java](/akka-docs/src/test/java/jdocs/stream/operators/SourceOrFlow.java) { #mergePreferred }


## Reactive Streams semantics

@@@div { .callout }

**emits** when one of the inputs has an element available, preferring a defined input if multiple have elements available

**backpressures** when downstream backpressures

**completes** when all upstreams complete (This behavior is changeable to completing when any upstream completes by setting `eagerComplete=true`.)

@@@
