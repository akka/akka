# flatMapConcat

Transform each input element into a `Source` whose elements are then flattened into the output stream through concatenation.

@ref[Nesting and flattening operators](../index.md#nesting-and-flattening-operators)


## Signature

@apidoc[Flow.flatMapConcat](Flow) { scala="#flatMapConcat[T,M](f:Out=%3akka.stream.Graph[akka.stream.SourceShape[T],M]):FlowOps.this.Repr[T]" java="#flatMapConcat(akka.japi.function.Function)" } 

See also: @ref:[flatMapMerge](flatMapMerge.md)

## Description

Transform each input element into a `Source` whose elements are then flattened into the output stream through
concatenation. This means each source is fully consumed before consumption of the next source starts. 

## Example

In the following example `flatMapConcat` is used to create a `Source` for each incoming customerId. This could be, for example,
 a calculation or a query to a database. Each customer is then passed to `lookupCustomerEvents` which returns
a `Source`. All the events for a customer are delivered before moving to the next customer. 

Scala
:   @@snip [FlatMapConcat.scala](/akka-docs/src/test/scala/docs/stream/operators/sourceorflow/FlatMapConcat.scala) { #flatmap-concat}

Java
:   @@snip [FlatMapConcat.java](/akka-docs/src/test/java/jdocs/stream/operators/sourceorflow/FlatMapConcat.java) { #flatmap-concat }




## Reactive Streams semantics

@@@div { .callout }

**emits** when the current consumed substream has an element available

**backpressures** when downstream backpressures

**completes** when upstream completes and all consumed substreams complete

@@@

