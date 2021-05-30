# prepend

Prepends the given source to the flow, consuming it until completion before the original source is consumed.

@ref[Fan-in operators](../index.md#fan-in-operators)

## Signature

@apidoc[Source.prepend](Source) { scala="#prepend[U&gt;:Out,Mat2](that:akka.stream.Graph[akka.stream.SourceShape[U],Mat2]):FlowOps.this.Repr[U]" java="#prepend(akka.stream.Graph)" }
@apidoc[Flow.prepend](Flow) { scala="#prepend[U&gt;:Out,Mat2](that:akka.stream.Graph[akka.stream.SourceShape[U],Mat2]):FlowOps.this.Repr[U]" java="#prepend(akka.stream.Graph)" }


## Description

Prepends the given source to the flow, consuming it until completion before the original source is consumed.

@@@ note

    The `prepend` operator is for backwards compatibility reasons "detached" and will eagerly
    demand an element from both upstreams when the stream is materialized and will then have a
    one element buffer for each of the upstreams, this is most often not what you want, instead
    use @ref(prependLazy)[prependLazy.md]

@@@

If materialized values needs to be collected `prependMat` is available.

@@@ note

The `prepend` operator is for backwards compatibility reasons "detached" and will eagerly
demand an element from both upstreams when the stream is materialized and will then have a
one element buffer for each of the upstreams, this is not always what you want, if not,
use @ref(prependLazy)[prependLazy.md]

@@@

## Example
Scala
:   @@snip [FlowOrElseSpec.scala](/akka-stream-tests/src/test/scala/akka/stream/scaladsl/FlowPrependSpec.scala) { #prepend }

Java
:   @@snip [SourceOrFlow.java](/akka-docs/src/test/java/jdocs/stream/operators/SourceOrFlow.java) { #prepend }

## Reactive Streams semantics

@@@div { .callout }

**emits** when the given stream has an element available; if the given input completes, it tries the current one

**backpressures** when downstream backpressures

**completes** when all upstreams complete

@@@
