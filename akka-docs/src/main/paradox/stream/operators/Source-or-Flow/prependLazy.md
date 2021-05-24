# prependLazy

Prepends the given source to the flow, consuming it until completion before the original source is consumed.

@ref[Fan-in operators](../index.md#fan-in-operators)

## Signature

@apidoc[Source.prepend](Source) { scala="#prepend[U&gt;:Out,Mat2](that:akka.stream.Graph[akka.stream.SourceShape[U],Mat2]):FlowOps.this.Repr[U]" java="#prepend(akka.stream.Graph)" }
@apidoc[Flow.prepend](Flow) { scala="#prepend[U&gt;:Out,Mat2](that:akka.stream.Graph[akka.stream.SourceShape[U],Mat2]):FlowOps.this.Repr[U]" java="#prepend(akka.stream.Graph)" }


## Description

Prepends the given source to the flow, consuming it until completion before the original source is consumed.

If materialized values needs to be collected `prependMat` is available.

See also @ref[prepend](prepend.md) which is detached.

## Example
Scala
:   @@snip [FlowPrependSpec.scala](/akka-stream-tests/src/test/scala/akka/stream/scaladsl/FlowPrependSpec.scala) { #prependLazy }

Java
:   @@snip [SourceOrFlow.java](/akka-docs/src/test/java/jdocs/stream/operators/SourceOrFlow.java) { #prependLazy }

## Reactive Streams semantics

@@@div { .callout }

**emits** when the given stream has an element available; if the given input completes, it tries the current one

**backpressures** when downstream backpressures

**completes** when all upstreams complete

@@@
