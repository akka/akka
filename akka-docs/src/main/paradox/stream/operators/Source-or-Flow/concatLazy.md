# concatLazy

After completion of the original upstream the elements of the given source will be emitted.

@ref[Fan-in operators](../index.md#fan-in-operators)

## Signature

@apidoc[Source.concat](Source) { scala="#concatLazy[U&gt;:Out,Mat2](that:akka.stream.Graph[akka.stream.SourceShape[U],Mat2]):FlowOps.this.Repr[U]" java="#concatLazy(akka.stream.Graph)" }
@apidoc[Flow.concat](Flow) { scala="#concatLazy[U&gt;:Out,Mat2](that:akka.stream.Graph[akka.stream.SourceShape[U],Mat2]):FlowOps.this.Repr[U]" java="#concatLazy(akka.stream.Graph)" }


## Description

After completion of the original upstream the elements of the given source will be emitted.

Both streams will be materialized together, however, the given stream will be pulled for the first time only after the original upstream was completed. (In contrast, @ref:[`concat`](concat.md), introduces single-element buffers after both, original and given sources so that the given source is also pulled once immediately.)

To defer the materialization of the given source (or to completely avoid its materialization if the original upstream fails or cancels), wrap it into @ref:[`Source.lazySource`](../Source/lazySource.md).

If materialized values needs to be collected `concatLazyMat` is available.

## Example
Scala
:   @@snip [FlowConcatSpec.scala](/akka-stream-tests/src/test/scala/akka/stream/scaladsl/FlowConcatSpec.scala) { #concatLazy }

Java
:   @@snip [SourceOrFlow.java](/akka-docs/src/test/java/jdocs/stream/operators/SourceOrFlow.java) { #concatLazy }

## Reactive Streams semantics

@@@div { .callout }

**emits** when the current stream has an element available; if the current input completes, it tries the next one

**backpressures** when downstream backpressures

**completes** when all upstreams complete

@@@
