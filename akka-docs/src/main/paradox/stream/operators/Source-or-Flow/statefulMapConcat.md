# statefulMapConcat

Transform each element into zero or more elements that are individually passed downstream.

@ref[Simple operators](../index.md#simple-operators)

## Signature

@apidoc[Flow.statefulMapConcat](Flow) { scala="#statefulMapConcat[T](f:()=&gt;Out=&gt;scala.collection.immutable.Iterable[T]):FlowOps.this.Repr[T]" java="#statefulMapConcat(akka.japi.function.Creator)" } 

## Description

Transform each element into zero or more elements that are individually passed downstream. The difference to `mapConcat` is that
the transformation function is created from a factory for every materialization of the flow. This makes it possible to create and
use mutable state for the operation, each new materialization of the stream will have its own state.

For cases where no state is needed but only a way to emit zero or more elements for every incoming element you can use @ref:[mapConcat](mapConcat.md)

## Examples

In this first sample we keep a counter, and combine each element with an id that is unique for the stream materialization
(replicating the @ref:[zipWithIndex](zipWithIndex.md) operator):

Scala
:  @@snip [StatefulMapConcat.scala](/akka-docs/src/test/scala/docs/stream/operators/flow/StatefulMapConcat.scala) { #zip-with-index }

Java
:   @@snip [StatefulMapConcat.java](/akka-docs/src/test/java/jdocs/stream/operators/flow/StatefulMapConcat.java) { #zip-with-index }

In this sample we let the value of the elements have an effect on the following elements, if an element starts
with `blacklist:word` we add it to a black list and filter out any subsequent entries of `word`:

Scala
:  @@snip [StatefulMapConcat.scala](/akka-docs/src/test/scala/docs/stream/operators/flow/StatefulMapConcat.scala) { #blacklist }

Java
:   @@snip [StatefulMapConcat.java](/akka-docs/src/test/java/jdocs/stream/operators/flow/StatefulMapConcat.java) { #blacklist }

For cases where there is a need to emit elements based on the state when the stream ends, it is possible to add an extra
element signalling the end of the stream before the `statefulMapConcat` operator.

In this sample we collect all elements starting with the letter `b` and emit those once we have reached the end of the stream using
a special end element. The end element is a special string to keep the sample concise, in a real application it may make sense to use types instead.

Scala
:  @@snip [StatefulMapConcat.scala](/akka-docs/src/test/scala/docs/stream/operators/flow/StatefulMapConcat.scala) { #bs-last }

Java
:   @@snip [StatefulMapConcat.java](/akka-docs/src/test/java/jdocs/stream/operators/flow/StatefulMapConcat.java) { #bs-last }

When defining aggregates like this you should consider if it is safe to let the state grow without bounds or if you should
rather drop elements or throw an exception if the collected set of elements grows too big.

For even more fine grained capabilities than can be achieved with `statefulMapConcat` take a look at @ref[stream customization](../../stream-customize.md).


## Reactive Streams semantics

@@@div { .callout }

**emits** when the mapping function returns an element or there are still remaining elements from the previously calculated collection

**backpressures** when downstream backpressures or there are still available elements from the previously calculated collection

**completes** when upstream completes and all remaining elements has been emitted

@@@
