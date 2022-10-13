# concat

After completion of the original upstream the elements of the given source will be emitted.

@ref[Fan-in operators](../index.md#fan-in-operators)

## Signature

@apidoc[Source.concat](Source) { scala="#concat[U&gt;:Out,Mat2](that:akka.stream.Graph[akka.stream.SourceShape[U],Mat2]):FlowOps.this.Repr[U]" java="#concat(akka.stream.Graph)" }
@apidoc[Flow.concat](Flow) { scala="#concat[U&gt;:Out,Mat2](that:akka.stream.Graph[akka.stream.SourceShape[U],Mat2]):FlowOps.this.Repr[U]" java="#concat(akka.stream.Graph)" }


## Description

After completion of the original upstream the elements of the given source will be emitted.

Both streams will be materialized together.

@@@ note

   The `concat` operator is for backwards compatibility reasons "detached" and will eagerly 
   demand an element from both upstreams when the stream is materialized and will then have a 
   one element buffer for each of the upstreams, this is most often not what you want, instead
   use @ref:[`concatLazy`](concatLazy.md)

@@@

## Example
Scala
:   @@snip [FlowConcatSpec.scala](/akka-stream-tests/src/test/scala/akka/stream/scaladsl/FlowConcatSpec.scala) { #concat }

Java
:   @@snip [SourceOrFlow.java](/akka-docs/src/test/java/jdocs/stream/operators/SourceOrFlow.java) { #concat }

## Reactive Streams semantics

@@@div { .callout }

**emits** when the current stream has an element available; if the current input completes, it tries the next one

**backpressures** when downstream backpressures

**completes** when all upstreams complete

@@@
