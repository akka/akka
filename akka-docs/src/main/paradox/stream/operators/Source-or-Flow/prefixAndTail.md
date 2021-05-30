# prefixAndTail

Take up to *n* elements from the stream (less than *n* only if the upstream completes before emitting *n* elements) and returns a pair containing a strict sequence of the taken element and a stream representing the remaining elements.

@ref[Nesting and flattening operators](../index.md#nesting-and-flattening-operators)

## Signature

@apidoc[Source.prefixAndTail](Source) { scala="#prefixAndTail[U&gt;:Out](n:Int):FlowOps.this.Repr[(scala.collection.immutable.Seq[Out],akka.stream.scaladsl.Source[U,akka.NotUsed])]" java="#prefixAndTail(int)" }
@apidoc[Flow.prefixAndTail](Flow) { scala="#prefixAndTail[U&gt;:Out](n:Int):FlowOps.this.Repr[(scala.collection.immutable.Seq[Out],akka.stream.scaladsl.Source[U,akka.NotUsed])]" java="#prefixAndTail(int)" }


## Description

Take up to *n* elements from the stream (less than *n* only if the upstream completes before emitting *n* elements)
and returns a pair containing a strict sequence of the taken element and a stream representing the remaining elements.

## Reactive Streams semantics

@@@div { .callout }

**emits** when the configured number of prefix elements are available. Emits this prefix, and the rest as a substream

**backpressures** when downstream backpressures or substream backpressures

**completes** when prefix elements has been consumed and substream has been consumed

@@@


