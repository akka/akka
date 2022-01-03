# merge

Merge multiple sources.

@ref[Fan-in operators](../index.md#fan-in-operators)

## Signature

@apidoc[Source.merge](Source) { scala="#merge[U&gt;:Out,M](that:akka.stream.Graph[akka.stream.SourceShape[U],M],eagerComplete:Boolean):FlowOps.this.Repr[U]" java="#merge(akka.stream.Graph)" java="#merge(akka.stream.Graph,boolean)" }
@apidoc[Flow.merge](Flow) { scala="#merge[U&gt;:Out,M](that:akka.stream.Graph[akka.stream.SourceShape[U],M],eagerComplete:Boolean):FlowOps.this.Repr[U]" java="#merge(akka.stream.Graph)" java="#merge(akka.stream.Graph,boolean)" }


## Description

Merge multiple sources. Picks elements randomly if all sources has elements ready.

## Example
Scala
:   @@snip [FlowMergeSpec.scala](/akka-stream-tests/src/test/scala/akka/stream/scaladsl/FlowMergeSpec.scala) { #merge }

Java
:   @@snip [SourceOrFlow.java](/akka-docs/src/test/java/jdocs/stream/operators/SourceOrFlow.java) { #merge }

## Reactive Streams semantics

@@@div { .callout }

**emits** when one of the inputs has an element available

**backpressures** when downstream backpressures

**completes** when all upstreams complete (This behavior is changeable to completing when any upstream completes by setting `eagerComplete=true`.)

@@@
