# mergeLatest

Merge multiple sources.

@ref[Fan-in operators](../index.md#fan-in-operators)

## Signature

@apidoc[Flow.mergeLatest](Flow) { scala="#mergeLatest[U%3E:Out,M](that:akka.stream.Graph[akka.stream.SourceShape[U],M],eagerComplete:Boolean):FlowOps.this.Repr[scala.collection.immutable.Seq[U]]" java="#mergeLatest(akka.stream.Graph,boolean)" } 

## Description

MergeLatest joins elements from N input streams into stream of lists of size N.
The i-th element in list is the latest emitted element from i-th input stream.
MergeLatest emits list for each element emitted from some input stream,
but only after each input stream emitted at least one element
If `eagerComplete` is set to true then it completes as soon as the first upstream
completes otherwise when all upstreams complete.

## Example

This example takes a stream of prices and quantities and emits the price each time the
price of quantity changes:

Scala
:   @@snip [MergeLatest.scala](/akka-docs/src/test/scala/docs/stream/operators/sourceorflow/MergeLatest.scala) { #mergeLatest }
 
Java
:   @@snip [MergeLatest.java](/akka-docs/src/test/java/jdocs/stream/operators/sourceorflow/MergeLatest.java) { #mergeLatest }
 

## Reactive Streams semantics

@@@div { .callout }

**emits** when element is available from some input and each input emits at least one element from stream start

**completes** all upstreams complete (eagerClose=false) or one upstream completes (eagerClose=true)
@@@

