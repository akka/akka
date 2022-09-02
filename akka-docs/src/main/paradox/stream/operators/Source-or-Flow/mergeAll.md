# mergeAll

Merge multiple sources.

@ref[Fan-in operators](../index.md#fan-in-operators)

## Signature

@apidoc[Source.mergeAll](Source) { scala="#mergeAll[U&gt;:Out,M](those:immutable.Seq[akka.stream.Graph[akka.stream.SourceShape[U],M]],eagerComplete:Boolean):FlowOps.this.Repr[U]" java="#mergeAll(java.util.List,boolean)" }
@apidoc[Flow.mergeAll](Flow) { scala="#mergeAll[U&gt;:Out,M](those:immutable.Seq[akka.stream.Graph[akka.stream.SourceShape[U],M]],eagerComplete:Boolean):FlowOps.this.Repr[U]" java="#mergeAll(java.util.List,boolean)" }

## Description

Merge multiple sources. Picks elements randomly if all sources has elements ready.

## Example
Scala
:   @@snip [FlowMergeSpec.scala](/akka-stream-tests/src/test/scala/akka/stream/scaladsl/FlowMergeAllSpec.scala) { #merge-all }

Java
:   @@snip [SourceOrFlow.java](/akka-docs/src/test/java/jdocs/stream/operators/SourceOrFlow.java) { #merge-all }

## Reactive Streams semantics

@@@div { .callout }

**emits** when one of the inputs has an element available

**backpressures** when downstream backpressures

**completes** when all upstreams complete (This behavior is changeable to completing when any upstream completes by setting `eagerComplete=true`.)

@@@
