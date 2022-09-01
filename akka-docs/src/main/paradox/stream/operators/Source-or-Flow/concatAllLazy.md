# concatAllLazy

After completion of the original upstream the elements of the given sources will be emitted sequentially.

@ref[Fan-in operators](../index.md#fan-in-operators)

## Signature

@apidoc[Source.concatAllLazy](Source) { scala="#concatAllLazy[U&gt;:Out](those:akka.stream.Graph[akka.stream.SourceShape[U],_]*):FlowOps.this.Repr[U]" java="#concatAllLazy(akka.stream.Graph*)" }
@apidoc[Flow.concatAllLazy](Flow) { scala="#concatAllLazy[U&gt;:Out](those:akka.stream.Graph[akka.stream.SourceShape[U],_]*):FlowOps.this.Repr[U]" java="#concatAllLazy(akka.stream.Graph*)" }


## Description

After completion of the original upstream the elements of the given sources will be emitted sequentially.

Both streams will be materialized together, however, the given streams will be pulled for the first time only after the original upstream was completed. 

To defer the materialization of the given sources (or to completely avoid its materialization if the original upstream fails or cancels), wrap it into @ref:[`Source.lazySource`](../Source/lazySource.md).

## Example
Scala
:   @@snip [FlowConcatSpec.scala](/akka-stream-tests/src/test/scala/akka/stream/scaladsl/FlowConcatAllLazySpec.scala) { #concatAllLazy }

Java
:   @@snip [SourceOrFlow.java](/akka-docs/src/test/java/jdocs/stream/operators/SourceOrFlow.java) { #concatAllLazy }

## Reactive Streams semantics

@@@div { .callout }

**emits** when the current stream has an element available; if the current input completes, it tries the next one

**backpressures** when downstream backpressures

**completes** when all upstreams complete

@@@
