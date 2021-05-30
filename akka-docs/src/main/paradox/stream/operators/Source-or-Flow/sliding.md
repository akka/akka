# sliding

Provide a sliding window over the incoming stream and pass the windows as groups of elements downstream.

@ref[Simple operators](../index.md#simple-operators)

## Signature

@apidoc[Flow.sliding](Flow) { scala="#sliding(n:Int,step:Int):FlowOps.this.Repr[scala.collection.immutable.Seq[Out]]" java="#sliding(int,int)" } 

## Description

Provide a sliding window over the incoming stream and pass the windows as groups of elements downstream.

Note: the last window might be smaller than the requested size due to end of stream.

## Examples

In this first sample we just see the behavior of the operator itself, first with a window of 2 elements and @scala[the default
`step` which is 1]@java[a step value of 1].

Scala
:   @@snip [Sliding.scala](/akka-docs/src/test/scala/docs/stream/operators/sourceorflow/Sliding.scala) { #sliding-1 }

Java
:   @@snip [Sliding.java](/akka-docs/src/test/java/jdocs/stream/operators/sourceorflow/Sliding.java) { #sliding-1 }

If the stream stops without having seen enough elements to fill a window, the last window will have as many elements
was emitted before the stream ended. Here we also provide a step to move two elements forward for each window:   

Scala
:   @@snip [Sliding.scala](/akka-docs/src/test/scala/docs/stream/operators/sourceorflow/Sliding.scala) { #sliding-2 }

Java
:   @@snip [Sliding.java](/akka-docs/src/test/java/jdocs/stream/operators/sourceorflow/Sliding.java) { #sliding-2 }

One use case for sliding is to implement a moving average, here we do that with a "period" of `5`:

Scala
:   @@snip [Sliding.scala](/akka-docs/src/test/scala/docs/stream/operators/sourceorflow/Sliding.scala) { #moving-average }

Java
:   @@snip [Sliding.java](/akka-docs/src/test/java/jdocs/stream/operators/sourceorflow/Sliding.java) { #moving-average }

Sliding can also be used to do simple windowing, see @ref[splitAfter](splitAfter.md).


## Reactive Streams semantics

@@@div { .callout }

**emits** the specified number of elements has been accumulated or upstream completed

**backpressures** when a group has been assembled and downstream backpressures

**completes** when upstream completes

@@@

