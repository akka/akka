# mapConcat

Transform each element into zero or more elements that are individually passed downstream.

@ref[Simple operators](../index.md#simple-operators)

## Signature

@apidoc[Source.mapConcat](Source) { scala="#mapConcat[T](f:Out=&gt;scala.collection.immutable.Iterable[T]):FlowOps.this.Repr[T]" java="#mapConcat(akka.japi.function.Function)" }
@apidoc[Flow.mapConcat](Flow) { scala="#mapConcat[T](f:Out=&gt;scala.collection.immutable.Iterable[T]):FlowOps.this.Repr[T]" java="#mapConcat(akka.japi.function.Function)" }


## Description

Transform each element into zero or more elements that are individually passed downstream.
This can be used to flatten collections into individual stream elements.
Returning an empty iterable results in zero elements being passed downstream
rather than the stream being cancelled.

See also @ref:[statefulMapConcat](statefulMapConcat.md)

## Example

The following takes a stream of integers and emits each element twice downstream.

Scala
:  @@snip [MapConcat.scala](/akka-docs/src/test/scala/docs/stream/operators/sourceorflow/MapConcat.scala) { #map-concat }

Java
:  @@snip [MapConcat.java](/akka-docs/src/test/java/jdocs/stream/operators/sourceorflow/MapConcat.java) { #map-concat }


## Reactive Streams semantics

@@@div { .callout }

**emits** when the mapping function returns an element or there are still remaining elements from the previously calculated collection

**backpressures** when downstream backpressures or there are still available elements from the previously calculated collection

**completes** when upstream completes and all remaining elements has been emitted

@@@

